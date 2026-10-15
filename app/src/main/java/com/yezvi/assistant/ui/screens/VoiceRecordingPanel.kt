package com.yezvi.assistant.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.domain.speech.RecognitionFailureReason
import com.yezvi.assistant.domain.speech.SpeechToTextError
import com.yezvi.assistant.domain.speech.SpeechToTextState
import com.yezvi.assistant.presentation.capture.VoiceTranscriptDraftState
import com.yezvi.assistant.ui.theme.Motion
import com.yezvi.assistant.ui.theme.motionDurationMillis
import com.yezvi.assistant.ui.theme.rememberReduceMotion

/** Renders actual session snapshots; animation only marks a change of state or text area size. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceRecordingPanel(
    state: VoiceTranscriptDraftState,
    stopRequested: Boolean,
    cancelling: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = rememberReduceMotion(),
    pauseSupported: Boolean = false,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    val recognizing = state as? VoiceTranscriptDraftState.Recognizing
    val speechState = recognizing?.speechState
    val mixedOriginBlocked = when (state) {
        is VoiceTranscriptDraftState.Candidate -> state.draft.containsMixedTextAndVoice
        is VoiceTranscriptDraftState.SaveFailed -> state.draft.containsMixedTextAndVoice
        else -> false
    }
    val canStart = when (state) {
        VoiceTranscriptDraftState.Idle,
        is VoiceTranscriptDraftState.Discarded,
        is VoiceTranscriptDraftState.EmptyTranscript,
        is VoiceTranscriptDraftState.RecognitionFailed,
        is VoiceTranscriptDraftState.Saved -> true
        else -> false
    }
    val canStop = recognizing != null && !stopRequested &&
        (speechState is SpeechToTextState.Listening || speechState is SpeechToTextState.Transcribing ||
            speechState is SpeechToTextState.Paused)
    val isPaused = speechState is SpeechToTextState.Paused
    val canPause = pauseSupported && recognizing != null && !stopRequested &&
        (speechState is SpeechToTextState.Listening || speechState is SpeechToTextState.Transcribing)
    val toggleLabel = when {
        canStart -> R.string.capture_voice_start
        isPaused -> R.string.capture_voice_resume
        canPause -> R.string.capture_voice_pause
        else -> R.string.capture_voice_start
    }
    val canCancel = recognizing != null || state is VoiceTranscriptDraftState.Candidate ||
        state is VoiceTranscriptDraftState.AwaitingTextDraftDecision || state is VoiceTranscriptDraftState.SaveFailed
    val status = when {
        cancelling -> R.string.capture_voice_cancelling
        recognizing != null && stopRequested -> R.string.capture_voice_transcribing
        else -> when (state) {
            VoiceTranscriptDraftState.Idle -> R.string.capture_voice_idle
            is VoiceTranscriptDraftState.Recognizing -> when (speechState) {
                is SpeechToTextState.Listening -> R.string.capture_voice_listening
                is SpeechToTextState.Paused -> R.string.capture_voice_paused
                is SpeechToTextState.Transcribing -> R.string.capture_voice_transcribing
                else -> R.string.capture_voice_preparing
            }
            is VoiceTranscriptDraftState.Candidate,
            is VoiceTranscriptDraftState.AwaitingTextDraftDecision -> R.string.capture_voice_result
            is VoiceTranscriptDraftState.Discarded -> R.string.capture_voice_cancelled
            is VoiceTranscriptDraftState.EmptyTranscript,
            is VoiceTranscriptDraftState.RecognitionFailed -> R.string.capture_voice_error
            is VoiceTranscriptDraftState.Saving -> R.string.capture_saving_status
            is VoiceTranscriptDraftState.Saved -> R.string.capture_saved_status
            is VoiceTranscriptDraftState.SaveFailed -> R.string.capture_failed_status
        }
    }
    val detail = when {
        cancelling -> R.string.capture_voice_cancelling_detail
        mixedOriginBlocked -> R.string.capture_voice_mixed_origin_blocked
        recognizing != null && stopRequested -> R.string.capture_voice_finishing_detail
        else -> when (state) {
            is VoiceTranscriptDraftState.Recognizing -> when (speechState) {
                is SpeechToTextState.Listening -> R.string.capture_voice_listening_detail
                is SpeechToTextState.Paused -> R.string.capture_voice_paused_detail
                is SpeechToTextState.Transcribing -> R.string.capture_voice_processing_detail
                else -> R.string.capture_voice_preparing_detail
            }
            is VoiceTranscriptDraftState.Candidate,
            is VoiceTranscriptDraftState.AwaitingTextDraftDecision -> R.string.capture_voice_result_detail
            is VoiceTranscriptDraftState.Discarded -> R.string.capture_voice_cancelled_detail
            is VoiceTranscriptDraftState.EmptyTranscript -> R.string.capture_voice_no_speech
            is VoiceTranscriptDraftState.RecognitionFailed -> state.cause.messageResource()
            else -> R.string.capture_voice_local_detail
        }
    }
    val transcript = when (state) {
        is VoiceTranscriptDraftState.Candidate -> state.draft.recognizedText
        is VoiceTranscriptDraftState.AwaitingTextDraftDecision -> state.draft.recognizedText
        is VoiceTranscriptDraftState.Recognizing -> when (speechState) {
            is SpeechToTextState.Listening -> speechState.partialTranscript?.text
            is SpeechToTextState.Paused -> speechState.partialTranscript?.text
            is SpeechToTextState.Transcribing -> speechState.partialTranscript?.text
            else -> null
        }
        else -> null
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .animateContentSize(tween(motionDurationMillis(Motion.ExpandCollapseMillis, reduceMotion)))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.capture_voice_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Crossfade(
                targetState = status,
                animationSpec = tween(motionDurationMillis(Motion.StateChangeMillis, reduceMotion)),
                label = "voice-session-status",
            ) { statusResource ->
                Text(
                    stringResource(statusResource),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("capture_voice_status").semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            Text(
                stringResource(detail),
                modifier = Modifier.testTag("capture_voice_detail").semantics {
                    if (mixedOriginBlocked) liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is VoiceTranscriptDraftState.RecognitionFailed || state is VoiceTranscriptDraftState.EmptyTranscript) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!transcript.isNullOrBlank() && !cancelling) {
                SelectionContainer {
                    Text(
                        transcript,
                        modifier = Modifier.testTag("capture_voice_transcript"),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (recognizing != null) {
                    Text(stringResource(R.string.capture_voice_partial), style = MaterialTheme.typography.labelMedium)
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = when {
                        canStart -> onStart
                        isPaused -> onResume
                        else -> onPause
                    },
                    enabled = enabled && !cancelling && (canStart || isPaused || canPause),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_voice_start"),
                ) { Text(stringResource(toggleLabel)) }
                OutlinedButton(
                    onClick = onStop,
                    enabled = enabled && canStop && !cancelling,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_voice_stop"),
                ) { Text(stringResource(R.string.capture_voice_stop)) }
                TextButton(
                    onClick = onCancel,
                    enabled = canCancel && !cancelling,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("capture_voice_cancel"),
                ) { Text(stringResource(R.string.capture_voice_cancel)) }
            }
        }
    }
}

private fun SpeechToTextError.messageResource(): Int = when (this) {
    SpeechToTextError.PermissionDenied -> R.string.capture_voice_permission_error
    SpeechToTextError.EngineUnavailable -> R.string.capture_voice_engine_error
    SpeechToTextError.OfflineModelMissing -> R.string.capture_voice_model_error
    is SpeechToTextError.RecognitionFailed -> when (reason) {
        RecognitionFailureReason.NO_SPEECH -> R.string.capture_voice_no_speech
        RecognitionFailureReason.LANGUAGE_NOT_SUPPORTED,
        RecognitionFailureReason.LANGUAGE_UNAVAILABLE -> R.string.capture_voice_language_error
        RecognitionFailureReason.AUDIO_CAPTURE -> R.string.capture_voice_audio_error
        RecognitionFailureReason.BUSY -> R.string.capture_voice_busy_error
        else -> R.string.capture_voice_recognition_error
    }
}
