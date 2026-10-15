package com.yezvi.assistant.presentation.intelligence

import com.yezvi.assistant.domain.intelligence.ProposalLifecycle
import com.yezvi.assistant.domain.intelligence.ProposalReviewReadiness
import com.yezvi.assistant.domain.intelligence.ProposalReviewState
import com.yezvi.assistant.domain.intelligence.TemporalResolutionReason
import com.yezvi.assistant.domain.intelligence.TemporalResolutionStatus

internal data class ProposalReviewSourceUi(
    val sourceEntryId: String,
    val revisionLabel: String,
    val originalText: String,
    val reviewedText: String,
    val capturedAtEpochMillis: Long,
    val timezoneId: String,
)

internal data class ProposalReviewEvidenceUi(
    val sourceEntryId: String,
    val revisionLabel: String,
    val quote: String,
    val startUtf16: Int,
    val endUtf16: Int,
)

internal data class ProposalReviewAssessmentUi(
    val basis: String?,
    val modality: String?,
    val polarity: String?,
)

internal data class ProposalReviewTemporalUi(
    val expression: String,
    val status: TemporalResolutionStatus,
    val reason: TemporalResolutionReason,
    val localDate: String?,
    val endLocalDate: String?,
    val localTime: String?,
    val endLocalTime: String?,
    val dayPart: String?,
    val timezoneId: String?,
)

internal data class ProposalReviewFieldUi(
    val pointer: String,
    val key: String,
    val value: String,
    val relatedProposalId: String? = null,
    val assessment: ProposalReviewAssessmentUi? = null,
    val evidence: List<ProposalReviewEvidenceUi> = emptyList(),
    val temporal: ProposalReviewTemporalUi? = null,
    /** A correction lives only in this screen session; the canonical document stays unchanged. */
    val localEdit: String? = null,
)

internal data class ProposalReviewCardUi(
    val extractionId: String,
    val proposalId: String,
    val category: String,
    val title: String,
    val fields: List<ProposalReviewFieldUi>,
    val sources: List<ProposalReviewSourceUi>,
    val reviewState: ProposalReviewState,
) {
    val key: String get() = "$extractionId/$proposalId"
}

internal sealed interface ProposalReviewScreenState {
    data object Loading : ProposalReviewScreenState
    data object Empty : ProposalReviewScreenState
    data class Content(
        val proposals: List<ProposalReviewCardUi>,
        val noProposalScenarios: Int,
    ) : ProposalReviewScreenState
    data class Error(val safeCode: String) : ProposalReviewScreenState
}

/** Kept out of Compose state: this object is the in-memory F4.06 lifecycle for each demo card. */
internal data class ActiveProposalReview(
    val card: ProposalReviewCardUi,
    val lifecycle: ProposalLifecycle,
    val readiness: ProposalReviewReadiness,
)

internal data class ProposalReviewDemoSession(
    val proposals: List<ActiveProposalReview>,
    val noProposalScenarios: Int,
) {
    fun screenState(): ProposalReviewScreenState = if (proposals.isEmpty()) {
        ProposalReviewScreenState.Empty
    } else {
        ProposalReviewScreenState.Content(proposals.map { it.card }, noProposalScenarios)
    }
}
