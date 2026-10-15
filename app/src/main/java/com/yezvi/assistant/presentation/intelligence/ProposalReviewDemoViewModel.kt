package com.yezvi.assistant.presentation.intelligence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yezvi.assistant.domain.intelligence.ExplicitUserReviewAction
import com.yezvi.assistant.domain.intelligence.ProposalLifecycleCommand
import com.yezvi.assistant.domain.intelligence.ProposalReviewState
import com.yezvi.assistant.domain.intelligence.ProposalTransitionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** F4.07 debug demonstration state. All decisions and field edits disappear with this ViewModel. */
internal class ProposalReviewDemoViewModel(
    loader: ProposalReviewDemoLoader,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ProposalReviewScreenState>(ProposalReviewScreenState.Loading)
    val uiState: StateFlow<ProposalReviewScreenState> = _uiState.asStateFlow()

    private var session: ProposalReviewDemoSession? = null
    private var actionSequence = 0L

    init {
        viewModelScope.launch {
            try {
                session = loader.load()
                _uiState.value = checkNotNull(session).screenState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never render an unvalidated fixture or leak parser/provider content in a message.
                _uiState.value = ProposalReviewScreenState.Error("DEMO_DATA_UNAVAILABLE")
            }
        }
    }

    fun saveLocalFieldEdit(proposalKey: String, pointer: String, editedText: String) {
        val current = session ?: return
        val updated = current.proposals.map { item ->
            if (item.card.key != proposalKey || item.lifecycle.state != ProposalReviewState.PROPOSED) {
                item
            } else {
                val fields = item.card.fields.map { field ->
                    if (field.pointer == pointer) field.copy(localEdit = editedText) else field
                }
                item.copy(card = item.card.copy(fields = fields))
            }
        }
        session = current.copy(proposals = updated)
        _uiState.value = checkNotNull(session).screenState()
    }

    fun decide(proposalKey: String, confirm: Boolean) {
        val current = session ?: return
        val actionNumber = actionSequence++
        val actionId = "demo-user-action-$actionNumber"
        val occurredAt = System.currentTimeMillis()
        val updated = current.proposals.map { item ->
            if (item.card.key != proposalKey) return@map item
            val explicitAction = ExplicitUserReviewAction.afterExplicitReview(actionId)
            val command = if (confirm) {
                ProposalLifecycleCommand.Confirm(actionId, occurredAt, explicitAction)
            } else {
                ProposalLifecycleCommand.Reject(actionId, occurredAt, explicitAction)
            }
            when (val result = item.lifecycle.transition(command)) {
                is ProposalTransitionResult.Applied -> item.copy(
                    lifecycle = result.lifecycle,
                    card = item.card.copy(reviewState = result.lifecycle.state),
                )
                is ProposalTransitionResult.AlreadyApplied -> item.copy(
                    lifecycle = result.lifecycle,
                    card = item.card.copy(reviewState = result.lifecycle.state),
                )
                is ProposalTransitionResult.NoChange,
                is ProposalTransitionResult.Rejected -> item
            }
        }
        session = current.copy(proposals = updated)
        _uiState.value = checkNotNull(session).screenState()
    }
}
