package com.yezvi.assistant.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yezvi.assistant.R
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.speech.SpeechToTextEngine
import com.yezvi.assistant.presentation.capture.CaptureSaveState
import com.yezvi.assistant.presentation.capture.CaptureViewModel
import com.yezvi.assistant.presentation.capture.ExistingTextDraftDecision
import com.yezvi.assistant.presentation.capture.VoiceCaptureSessionController
import com.yezvi.assistant.presentation.capture.VoiceTranscriptDraftCoordinator
import com.yezvi.assistant.presentation.capture.VoiceTranscriptDraftState
import com.yezvi.assistant.presentation.capture.MicrophonePermissionStatus
import com.yezvi.assistant.presentation.capture.resolveMicrophonePermissionStatus
import com.yezvi.assistant.presentation.capture.shouldOfferMicrophoneSettings
import com.yezvi.assistant.presentation.actions.AppDestination
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.ui.components.YervisIcon
import com.yezvi.assistant.ui.components.YervisPanel
import com.yezvi.assistant.ui.components.YervisSymbol
import com.yezvi.assistant.ui.theme.LocalVisualMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureScreen(
    callbacks: MemoryUiCallbacks,
    viewModel: CaptureViewModel,
    voiceEngineFactory: (Lifecycle, CoroutineScope) -> SpeechToTextEngine,
    voicePermissionCheck: (() -> Boolean)? = null,
    onNavigationHandlerChanged: (((AppDestination) -> Unit)?) -> Unit = {},
    onEditorFocusChanged: (Boolean) -> Unit = {},
) {
    val metrics = LocalVisualMetrics.current
    var draft by rememberSaveable { mutableStateOf("") }
    var editorValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var reviewInput by remember { mutableStateOf<CaptureInput?>(null) }
    var reviewInputIsVoice by remember { mutableStateOf(false) }
    var reviewInputHasMixedOrigin by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf<AppDestination?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner, viewModel) {
        val confirmation = viewModel.spokenConfirmation
        confirmation.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> confirmation.setForeground(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> confirmation.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            confirmation.setForeground(false)
        }
    }
    val platformPermissionCheck = remember(context) { { context.hasRecordAudioPermission() } }
    val currentPermissionCheck by rememberUpdatedState(voicePermissionCheck ?: platformPermissionCheck)
    val voiceController = remember(lifecycleOwner, voiceEngineFactory, scope) {
        VoiceCaptureSessionController(
            engine = voiceEngineFactory(lifecycleOwner.lifecycle, scope),
            scope = scope,
            hasRecordAudioPermission = { currentPermissionCheck() },
        )
    }
    val voiceCoordinator = remember(voiceController, viewModel, scope) {
        VoiceTranscriptDraftCoordinator(voiceController, viewModel, scope)
    }
    val voiceState by voiceCoordinator.state.collectAsStateWithLifecycle()
    val voiceDecision = voiceState as? VoiceTranscriptDraftState.AwaitingTextDraftDecision
    val voiceCandidate = when (val current = voiceState) {
        is VoiceTranscriptDraftState.Candidate -> current.draft
        is VoiceTranscriptDraftState.Saving -> current.draft
        is VoiceTranscriptDraftState.SaveFailed -> current.draft
        else -> null
    }
    val voiceCandidateEditable = voiceState is VoiceTranscriptDraftState.Candidate ||
        voiceState is VoiceTranscriptDraftState.SaveFailed
    val displayedDraft = voiceCandidate?.editableText ?: draft
    val voiceCandidateHasMixedOrigin = voiceCandidate?.containsMixedTextAndVoice == true
    var voiceStartJob by remember(voiceCoordinator) { mutableStateOf<Job?>(null) }
    var voiceStopRequested by remember(voiceCoordinator) { mutableStateOf(false) }
    var voiceCancelling by remember(voiceCoordinator) { mutableStateOf(false) }
    val isVoiceActive = voiceState is VoiceTranscriptDraftState.Recognizing
    val hasVoiceCandidate = voiceState is VoiceTranscriptDraftState.Candidate ||
        voiceState is VoiceTranscriptDraftState.AwaitingTextDraftDecision ||
        voiceState is VoiceTranscriptDraftState.SaveFailed
    val saveState by viewModel.uiState.collectAsStateWithLifecycle()
    val reviewSaveState = if (reviewInputHasMixedOrigin) {
        CaptureSaveState.Idle
    } else {
        reviewInput?.let(saveState::forCapture) ?: CaptureSaveState.Idle
    }
    val isSaving = saveState is CaptureSaveState.Saving || voiceState is VoiceTranscriptDraftState.Saving

    val cancelVoice: () -> Unit = {
        if (!voiceCancelling) {
            voiceCancelling = true
            // Cancelling this job interrupts model preparation without waiting behind start's lock.
            voiceStartJob?.cancel()
            scope.launch {
                try {
                    voiceCoordinator.discard()
                } finally {
                    voiceCancelling = false
                }
            }
        }
    }

    val currentBottomNavigationRequest = rememberUpdatedState<(AppDestination) -> Unit> { destination ->
        if (!isSaving) {
            scope.launch {
                val shouldConfirmDiscard = draft.isNotBlank() || voiceCandidate != null || isVoiceActive
                if (destination != AppDestination.CAPTURE && shouldConfirmDiscard) {
                    pendingNavigation = destination
                    showDiscardDialog = true
                } else {
                    if (destination != AppDestination.CAPTURE) {
                        voiceStartJob?.cancel()
                        voiceCoordinator.discard()
                    }
                    callbacks.onNavigateTo(destination)
                }
            }
        }
    }
    val stableBottomNavigationRequest = remember {
        { destination: AppDestination -> currentBottomNavigationRequest.value(destination) }
    }
    SideEffect { onNavigationHandlerChanged(stableBottomNavigationRequest) }
    DisposableEffect(onNavigationHandlerChanged) {
        onDispose { onNavigationHandlerChanged(null) }
    }
    DisposableEffect(onEditorFocusChanged) {
        onDispose { onEditorFocusChanged(false) }
    }

    DisposableEffect(lifecycleOwner, voiceCoordinator) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP &&
                voiceCoordinator.state.value is VoiceTranscriptDraftState.Recognizing
            ) {
                voiceStartJob?.cancel()
                scope.launch { voiceCoordinator.discard() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voiceStartJob?.cancel()
            // This cleanup must run even when the composition scope itself has been cancelled.
            scope.launch(NonCancellable) { voiceCoordinator.close() }
        }
    }

    LaunchedEffect(saveState, reviewInput) {
        if (reviewSaveState is CaptureSaveState.Saved) {
            draft = ""
        }
    }

    LaunchedEffect(displayedDraft) {
        if (editorValue.text != displayedDraft) {
            editorValue = TextFieldValue(
                text = displayedDraft,
                selection = TextRange(displayedDraft.length),
            )
        }
    }

    LaunchedEffect(lifecycleOwner) {
        // Opening Captura is a browse action; typing starts only after the user taps the editor.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    BackHandler(enabled = draft.isNotBlank() && !showReviewDialog && !isSaving) {
        pendingNavigation = null
        showDiscardDialog = true
    }

    BackHandler(enabled = isVoiceActive || hasVoiceCandidate || voiceCancelling) {
        cancelVoice()
    }

    if (showReviewDialog) {
        reviewInput?.let { input ->
            ReviewDialog(
                input = input,
                saveState = reviewSaveState,
                sourceLabel = when {
                    reviewInputHasMixedOrigin -> stringResource(R.string.capture_review_origin_mixed)
                    reviewInputIsVoice -> stringResource(R.string.capture_review_origin_voice)
                    else -> stringResource(R.string.capture_review_origin_text)
                },
                canSave = !reviewInputHasMixedOrigin,
                blockedMessage = if (reviewInputHasMixedOrigin) {
                    stringResource(R.string.capture_voice_mixed_origin_blocked)
                } else {
                    null
                },
                onSave = {
                    if (reviewInputIsVoice) {
                        scope.launch { voiceCoordinator.accept() }
                    } else {
                        viewModel.save(input)
                    }
                },
                onRetry = {
                    if (reviewInputIsVoice) {
                        scope.launch { voiceCoordinator.retrySave() }
                    } else {
                        viewModel.retry()
                    }
                },
                onDismiss = {
                    if (reviewSaveState !is CaptureSaveState.Saving) {
                        if (reviewSaveState is CaptureSaveState.Saved) {
                            draft = ""
                            reviewInput = null
                            reviewInputIsVoice = false
                            reviewInputHasMixedOrigin = false
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
                scope.launch {
                    voiceStartJob?.cancel()
                    voiceCoordinator.discard()
                    callbacks.onNavigateTo(pendingNavigation ?: AppDestination.TODAY)
                    pendingNavigation = null
                    draft = ""
                    editorValue = TextFieldValue("")
                    reviewInput = null
                    showReviewDialog = false
                    showDiscardDialog = false
                }
            },
            onDismiss = {
                pendingNavigation = null
                showDiscardDialog = false
            },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
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
            CaptureActionBar(
                    draft = displayedDraft,
                    clearLabel = if (voiceCandidate != null) {
                        R.string.capture_voice_discard_review
                    } else {
                        R.string.capture_clear
                    },
                    enabled = !isSaving && !isVoiceActive && !voiceCancelling && voiceDecision == null,
                    onReview = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        if (voiceCandidate != null) {
                            reviewInput = CaptureInput(
                                rawText = voiceCandidate.editableText,
                                sourceType = CaptureSourceType.VOICE,
                                capturedAtEpochMillis = voiceCandidate.session.capturedAtEpochMillis,
                                timezoneId = voiceCandidate.session.timezoneId,
                            )
                            reviewInputIsVoice = true
                            reviewInputHasMixedOrigin = voiceCandidateHasMixedOrigin
                        } else if (reviewInput?.rawText != draft || reviewInputIsVoice) {
                            reviewInput = CaptureInput(
                                rawText = draft,
                                sourceType = CaptureSourceType.TEXT,
                                capturedAtEpochMillis = System.currentTimeMillis(),
                                timezoneId = TimeZone.getDefault().id,
                            )
                            reviewInputIsVoice = false
                            reviewInputHasMixedOrigin = false
                        }
                        showReviewDialog = true
                    },
                    onClear = {
                        if (voiceCandidate != null) {
                            cancelVoice()
                        } else {
                            draft = ""
                            reviewInput = null
                            reviewInputIsVoice = false
                            reviewInputHasMixedOrigin = false
                        }
                    },
                modifier = Modifier.imePadding(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.ux_capture_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = metrics.screenPadding, vertical = 4.dp),
            )
            voiceDecision?.let { pending ->
                VoiceDraftDecisionDialog(
                    decision = pending,
                    onChoose = { choice ->
                        reviewInput = null
                        reviewInputIsVoice = false
                        reviewInputHasMixedOrigin = false
                        voiceCoordinator.resolveTextDraftDecision(choice)
                    },
                    onCancel = cancelVoice,
                )
            }
            VoiceRecordingPanel(
                state = voiceState,
                stopRequested = voiceStopRequested,
                cancelling = voiceCancelling,
                enabled = !isSaving && !showReviewDialog && !showDiscardDialog,
                pauseSupported = voiceController.supportsPause,
                onStart = {
                    if (voiceStartJob?.isActive != true && !voiceCancelling) {
                        viewModel.spokenConfirmation.interrupt()
                        focusManager.clearFocus()
                        keyboard?.hide()
                        voiceStopRequested = false
                        val textSnapshot = draft
                        val continuation = (voiceState as? VoiceTranscriptDraftState.Candidate)?.draft
                        val selection = editorValue.selection
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            if (continuation != null) {
                                voiceCoordinator.continueAt(
                                    draft = continuation,
                                    selectionStart = selection.min,
                                    selectionEnd = selection.max,
                                )
                            } else {
                                voiceCoordinator.start(existingTextDraft = textSnapshot)
                            }
                        }
                        voiceStartJob = job
                        job.start()
                    }
                },
                onStop = {
                    if (!voiceStopRequested) {
                        // Sherpa can emit Listening while draining its final VAD segments. Keep
                        // the UI in processing after a real stop request until the terminal state.
                        voiceStopRequested = true
                        scope.launch { voiceCoordinator.stop() }
                    }
                },
                onCancel = cancelVoice,
                onPause = { scope.launch { voiceCoordinator.pause() } },
                onResume = { scope.launch { voiceCoordinator.resume() } },
                modifier = Modifier.padding(horizontal = metrics.screenPadding, vertical = 8.dp),
            )
            VoicePermissionControl(
                hasRecordAudioPermission = { currentPermissionCheck() },
                persistPermissionHistory = voicePermissionCheck == null,
                onPermissionRevoked = {
                    scope.launch { voiceController.onPermissionRevoked() }
                },
            )
            OutlinedTextField(
                value = editorValue,
                onValueChange = {
                    editorValue = it
                    if (voiceCandidateEditable) {
                        voiceCoordinator.editText(it.text)
                    } else {
                        draft = it.text
                    }
                    if (reviewInput?.rawText != it.text) {
                        reviewInput = null
                        reviewInputIsVoice = false
                        reviewInputHasMixedOrigin = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = metrics.screenPadding, vertical = metrics.itemSpacing)
                    .onFocusChanged { onEditorFocusChanged(it.isFocused) }
                    .testTag("capture_text_field"),
                enabled = !isSaving && !isVoiceActive && !voiceCancelling &&
                    !showReviewDialog && voiceDecision == null &&
                    (voiceCandidate == null || voiceCandidateEditable),
                label = { Text(text = stringResource(R.string.capture_label)) },
                placeholder = { Text(text = stringResource(R.string.capture_hint)) },
                supportingText = { Text(text = stringResource(R.string.capture_draft_notice)) },
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                minLines = 7,
                maxLines = Int.MAX_VALUE,
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptureActionBar(
    draft: String,
    enabled: Boolean,
    clearLabel: Int = R.string.capture_clear,
    onReview: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalVisualMetrics.current
    YervisPanel(
        modifier = modifier.fillMaxWidth().padding(horizontal = metrics.screenPadding, vertical = 8.dp),
        highlighted = true,
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = onClear,
                enabled = draft.isNotBlank() && enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("capture_clear_action"),
            ) {
                Text(text = stringResource(clearLabel))
            }
            Button(
                onClick = onReview,
                enabled = draft.isNotBlank() && enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("capture_review_action"),
            ) {
                Text(text = stringResource(R.string.capture_review))
                YervisIcon(YervisSymbol.Next, modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
        }
    }
}

@Composable
private fun VoicePermissionControl(
    hasRecordAudioPermission: () -> Boolean,
    persistPermissionHistory: Boolean,
    onPermissionRevoked: () -> Unit,
) {
    val metrics = LocalVisualMetrics.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionPreferences = remember(context) {
        context.applicationContext.getSharedPreferences(
            MICROPHONE_PERMISSION_PREFERENCES,
            Context.MODE_PRIVATE,
        )
    }
    val currentPermissionCheck by rememberUpdatedState(hasRecordAudioPermission)
    val currentOnPermissionRevoked by rememberUpdatedState(onPermissionRevoked)
    val initiallyGranted = remember(context) { currentPermissionCheck() }
    var permissionGranted by rememberSaveable(persistPermissionHistory) { mutableStateOf(initiallyGranted) }
    var permissionRequested by rememberSaveable(persistPermissionHistory) {
        mutableStateOf(
            persistPermissionHistory && permissionPreferences.getBoolean(MICROPHONE_PERMISSION_REQUESTED, false),
        )
    }
    var previouslyGranted by rememberSaveable(persistPermissionHistory) {
        mutableStateOf(
            persistPermissionHistory && (
                initiallyGranted || permissionPreferences.getBoolean(
                    MICROPHONE_PERMISSION_PREVIOUSLY_GRANTED,
                    false,
                )
            ),
        )
    }
    var permissionResponse by rememberSaveable { mutableStateOf<Boolean?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val actualGrant = granted && currentPermissionCheck()
        permissionGranted = actualGrant
        if (actualGrant && persistPermissionHistory) {
            previouslyGranted = true
            permissionPreferences.edit()
                .putBoolean(MICROPHONE_PERMISSION_PREVIOUSLY_GRANTED, true)
                .apply()
        }
        permissionResponse = actualGrant
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val actualGrant = currentPermissionCheck()
                if (permissionGranted && !actualGrant) currentOnPermissionRevoked()
                permissionGranted = actualGrant
                if (actualGrant && persistPermissionHistory) {
                    previouslyGranted = true
                    permissionPreferences.edit()
                        .putBoolean(MICROPHONE_PERMISSION_PREVIOUSLY_GRANTED, true)
                        .apply()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionStatus = resolveMicrophonePermissionStatus(
        permissionGranted = permissionGranted,
        permissionRequested = permissionRequested,
        previouslyGranted = previouslyGranted,
    )
    // The result key recomputes Android's rationale after each permission response.
    val shouldShowRationale = remember(
        activity,
        permissionResponse,
        permissionGranted,
        permissionRequested,
    ) {
        activity?.let { currentActivity ->
            ActivityCompat.shouldShowRequestPermissionRationale(
                currentActivity,
                Manifest.permission.RECORD_AUDIO,
            )
        } ?: false
    }
    val openSettings = shouldOfferMicrophoneSettings(
        permissionRequested = permissionRequested,
        permissionGranted = permissionGranted,
        shouldShowRationale = shouldShowRationale,
    )
    val statusMessage = when (permissionStatus) {
        MicrophonePermissionStatus.NOT_REQUESTED -> R.string.capture_voice_permission_explanation
        MicrophonePermissionStatus.GRANTED -> R.string.capture_voice_permission_granted
        MicrophonePermissionStatus.DENIED -> R.string.capture_voice_permission_denied
        MicrophonePermissionStatus.REVOKED -> R.string.capture_voice_permission_revoked
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = metrics.screenPadding, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            YervisIcon(
                YervisSymbol.Shield,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(statusMessage),
                modifier = Modifier.weight(1f).testTag("capture_voice_permission_status").semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (permissionStatus != MicrophonePermissionStatus.GRANTED) {
            val actionLabel = when {
                openSettings -> R.string.capture_voice_permission_settings
                permissionStatus == MicrophonePermissionStatus.NOT_REQUESTED ->
                    R.string.capture_voice_permission_request
                else -> R.string.capture_voice_permission_retry
            }
            TextButton(
                onClick = {
                    if (openSettings) {
                        context.openApplicationSettings()
                    } else {
                        permissionRequested = true
                        if (persistPermissionHistory) {
                            permissionPreferences.edit()
                                .putBoolean(MICROPHONE_PERMISSION_REQUESTED, true)
                                .apply()
                        }
                        permissionResponse = null
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("capture_voice_permission_button"),
            ) {
                Text(text = stringResource(actionLabel))
            }
        }
    }
}

private fun Context.hasRecordAudioPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openApplicationSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
    }
    findActivity()?.startActivity(intent)
}

private const val MICROPHONE_PERMISSION_PREFERENCES = "yezvi_microphone_permission"
private const val MICROPHONE_PERMISSION_REQUESTED = "permission_requested"
private const val MICROPHONE_PERMISSION_PREVIOUSLY_GRANTED = "permission_previously_granted"

@Composable
private fun ReviewDialog(
    input: CaptureInput,
    saveState: CaptureSaveState,
    sourceLabel: String = if (input.sourceType == CaptureSourceType.TEXT) "Texto" else "Voz",
    canSave: Boolean = true,
    blockedMessage: String? = null,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isSaving = saveState is CaptureSaveState.Saving
    val reviewDate = remember(input.capturedAtEpochMillis, input.timezoneId) {
        formatReviewDate(input.capturedAtEpochMillis, input.timezoneId)
    }
    val statusMessage = when {
        blockedMessage != null -> R.string.ux_capture_save_blocked
        else -> when (saveState) {
            CaptureSaveState.Idle -> R.string.capture_review_notice
            is CaptureSaveState.Saving -> R.string.capture_saving_status
            is CaptureSaveState.Saved -> R.string.capture_saved_status
            is CaptureSaveState.Error -> R.string.capture_failed_status
        }
    }
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(text = stringResource(R.string.capture_review_title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        text = input.rawText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("capture_review_body"),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        R.string.capture_review_meta,
                        sourceLabel,
                        reviewDate,
                        input.timezoneId,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (blockedMessage != null) {
                    Text(
                        text = blockedMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("capture_mixed_origin_blocked"),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Surface(
                    color = when {
                        blockedMessage != null || saveState is CaptureSaveState.Error -> MaterialTheme.colorScheme.errorContainer
                        saveState is CaptureSaveState.Saved -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        val statusColor = when {
                            blockedMessage != null || saveState is CaptureSaveState.Error -> MaterialTheme.colorScheme.onErrorContainer
                            saveState is CaptureSaveState.Saved -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        YervisIcon(
                            when (saveState) {
                                is CaptureSaveState.Saved -> YervisSymbol.Check
                                is CaptureSaveState.Saving -> YervisSymbol.Clock
                                else -> YervisSymbol.Info
                            },
                            modifier = Modifier.size(20.dp),
                            tint = statusColor,
                        )
                        Text(
                            text = stringResource(statusMessage),
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                            modifier = Modifier.weight(1f).testTag("capture_save_status").semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (saveState) {
                CaptureSaveState.Idle -> Button(
                    onClick = onSave,
                    enabled = canSave,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_save_button"),
                ) {
                    Text(text = stringResource(if (canSave) R.string.capture_save else R.string.capture_save_unavailable))
                }
                is CaptureSaveState.Saving -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_save_button"),
                ) {
                    Text(text = stringResource(R.string.capture_saving_action))
                }
                is CaptureSaveState.Saved -> TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_finish_button"),
                ) {
                    Text(text = stringResource(R.string.capture_done))
                }
                is CaptureSaveState.Error -> Button(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_retry_button"),
                ) {
                    Text(text = stringResource(R.string.capture_retry))
                }
            }
        },
        dismissButton = {
            if (saveState is CaptureSaveState.Idle || saveState is CaptureSaveState.Error) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_close"),
                ) {
                    Text(text = stringResource(R.string.capture_close))
                }
            }
        },
    )
}

@Composable
private fun VoiceDraftDecisionDialog(
    decision: VoiceTranscriptDraftState.AwaitingTextDraftDecision,
    onChoose: (ExistingTextDraftDecision) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.capture_voice_draft_conflict_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.capture_voice_draft_conflict_message))
                Text(
                    text = decision.existingTextDraft,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("capture_voice_existing_draft"),
                )
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onChoose(ExistingTextDraftDecision.REPLACE_WITH_VOICE) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_voice_replace"),
                ) { Text(stringResource(R.string.capture_voice_replace_draft)) }
                TextButton(
                    onClick = { onChoose(ExistingTextDraftDecision.APPEND_VOICE) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_voice_append"),
                ) { Text(stringResource(R.string.capture_voice_append_draft)) }
                TextButton(
                    onClick = { onChoose(ExistingTextDraftDecision.KEEP_EXISTING) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_voice_keep_existing"),
                ) { Text(stringResource(R.string.capture_voice_keep_draft)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.capture_voice_cancel))
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
