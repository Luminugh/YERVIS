package com.yezvi.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.domain.intelligence.ProposalReviewState
import com.yezvi.assistant.domain.intelligence.TemporalResolutionStatus
import com.yezvi.assistant.presentation.intelligence.ProposalReviewCardUi
import com.yezvi.assistant.presentation.intelligence.ProposalReviewEvidenceUi
import com.yezvi.assistant.presentation.intelligence.ProposalReviewFieldUi
import com.yezvi.assistant.presentation.intelligence.ProposalReviewScreenState
import com.yezvi.assistant.presentation.intelligence.ProposalReviewSourceUi
import com.yezvi.assistant.presentation.intelligence.ProposalReviewTemporalUi
import com.yezvi.assistant.ui.components.YervisIcon
import com.yezvi.assistant.ui.components.YervisPanel
import com.yezvi.assistant.ui.components.YervisSymbol
import com.yezvi.assistant.ui.theme.LocalVisualMetrics
import com.yezvi.assistant.ui.theme.motionDurationMillis
import com.yezvi.assistant.ui.theme.rememberReduceMotion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun ProposalReviewDemoEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    YervisPanel(modifier = modifier.fillMaxWidth().testTag("proposal_review_demo_entry"), highlighted = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                YervisIcon(YervisSymbol.Decision, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = stringResource(R.string.proposal_review_entry_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            Text(
                text = stringResource(R.string.ux_proposal_entry_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("proposal_review_open"),
            ) { Text(stringResource(R.string.proposal_review_open)) }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ProposalReviewScreen(
    state: ProposalReviewScreenState,
    onBack: () -> Unit,
    onSaveLocalFieldEdit: (proposalKey: String, pointer: String, text: String) -> Unit,
    onDecision: (proposalKey: String, confirm: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.testTag("proposal_review_screen"),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.proposal_review_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        YervisIcon(YervisSymbol.Back, contentDescription = stringResource(R.string.proposal_review_back))
                    }
                },
            )
        },
    ) { insets ->
        when (state) {
            ProposalReviewScreenState.Loading -> ReviewMessage(
                title = stringResource(R.string.proposal_review_loading),
                tag = "proposal_review_loading",
                modifier = Modifier.fillMaxSize().padding(insets).padding(20.dp),
            )
            ProposalReviewScreenState.Empty -> ReviewMessage(
                title = stringResource(R.string.proposal_review_empty),
                tag = "proposal_review_empty",
                modifier = Modifier.fillMaxSize().padding(insets).padding(20.dp),
            )
            is ProposalReviewScreenState.Error -> ReviewMessage(
                title = stringResource(R.string.proposal_review_error),
                detail = stringResource(R.string.proposal_review_error_detail),
                tag = "proposal_review_error",
                modifier = Modifier.fillMaxSize().padding(insets).padding(20.dp),
            )
            is ProposalReviewScreenState.Content -> {
                // A field is the lazy unit, rather than the complete document and all its sources.
                // Large examples therefore compose only the information currently on screen.
                val proposalsById = remember(state.proposals) {
                    state.proposals.associateBy { it.extractionId to it.proposalId }
                }
                val metrics = LocalVisualMetrics.current
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(insets).imePadding().testTag("proposal_review_list"),
                    contentPadding = PaddingValues(start = metrics.screenPadding, top = 12.dp, end = metrics.screenPadding, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(metrics.itemSpacing),
                ) {
                    item(key = "demo-honesty", contentType = "notice") { DemoHonestyBanner() }
                    if (state.noProposalScenarios > 0) {
                        item(key = "no-proposals-count", contentType = "summary") {
                            Text(
                                text = stringResource(R.string.proposal_review_no_proposals_count, state.noProposalScenarios),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.testTag("proposal_review_no_proposals_count"),
                            )
                        }
                    }
                    state.proposals.forEach { proposal ->
                        item(key = "${proposal.key}/heading", contentType = "proposal-heading") {
                            ProposalReviewHeader(proposal)
                        }
                        items(proposal.fields, key = { "${proposal.key}/field/${it.pointer}" }, contentType = { "proposal-field" }) { field ->
                            ProposalReviewField(
                                proposalKey = proposal.key,
                                field = field,
                                relatedProposal = field.relatedProposalId?.let { proposalsById[proposal.extractionId to it] },
                                sources = proposal.sources,
                                canEdit = proposal.reviewState == ProposalReviewState.PROPOSED,
                                onSaveLocalEdit = { onSaveLocalFieldEdit(proposal.key, field.pointer, it) },
                            )
                        }
                        item(key = "${proposal.key}/sources", contentType = "proposal-sources") {
                            ProposalReviewSources(proposal)
                        }
                        item(key = "${proposal.key}/decision", contentType = "proposal-decision") {
                            ProposalReviewActions(proposal, onDecision = { onDecision(proposal.key, it) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoHonestyBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("proposal_review_demo_notice"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.proposal_review_demo_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.ux_proposal_demo_description), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.ux_proposal_no_actions),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("proposal_review_no_alarms"),
            )
        }
    }
}

@Composable
private fun ProposalReviewHeader(proposal: ProposalReviewCardUi) {
    val stateLabel = reviewStateLabel(proposal.reviewState)
    val pending = proposal.reviewState == ProposalReviewState.PROPOSED
    val observedPattern = remember(proposal.fields) {
        proposal.fields.any { it.key == "patternKind" && it.value == "OBSERVED" }
    }
    YervisPanel(
        modifier = Modifier.fillMaxWidth().testTag("proposal_review_card_${proposal.key}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                    YervisIcon(categorySymbol(proposal.category), modifier = Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = categoryLabel(proposal.category),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .semantics { stateDescription = stateLabel }
                            .testTag("proposal_review_category_${proposal.category}"),
                    )
                    Text(proposal.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                }
            }
            Surface(
                color = if (pending) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (pending) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag("proposal_review_status_${proposal.key}"),
                )
            }

            if (proposal.category == "REMINDER") {
                Text(
                    stringResource(R.string.proposal_review_reminder_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (observedPattern) {
                Text(
                    stringResource(R.string.proposal_review_observed_pattern),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("proposal_review_observed_hypothesis"),
                )
            }

            Text(
                stringResource(R.string.proposal_review_fields_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

@Composable
private fun ProposalReviewSources(proposal: ProposalReviewCardUi) {
    var expanded by rememberSaveable(proposal.key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DisclosureButton(
            expanded = expanded,
            onClick = { expanded = !expanded },
            label = stringResource(if (expanded) R.string.ux_proposal_hide_sources else R.string.ux_proposal_show_sources),
            tag = "proposal_review_sources_toggle_${proposal.key}",
        )
        ReviewDisclosure(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.proposal_review_original_heading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                proposal.sources.forEach { source -> key(source.sourceEntryId) { ReviewSource(source) } }
            }
        }
    }
}

@Composable
private fun ProposalReviewActions(proposal: ProposalReviewCardUi, onDecision: (Boolean) -> Unit) {
    var pendingDecision by rememberSaveable(proposal.key) { mutableStateOf<Boolean?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (proposal.reviewState == ProposalReviewState.PROPOSED) {
            Text(
                stringResource(R.string.ux_proposal_action_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = { pendingDecision = true },
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("proposal_review_confirm_${proposal.key}"),
                ) { Text(stringResource(R.string.proposal_review_confirm)) }
                OutlinedButton(
                    onClick = { pendingDecision = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("proposal_review_reject_${proposal.key}"),
                ) { Text(stringResource(R.string.proposal_review_reject)) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    pendingDecision?.let { confirm ->
        AlertDialog(
            onDismissRequest = { pendingDecision = null },
            title = {
                Text(stringResource(if (confirm) R.string.proposal_review_confirm_title else R.string.proposal_review_reject_title))
            },
            text = {
                Text(stringResource(if (confirm) R.string.proposal_review_confirm_message else R.string.proposal_review_reject_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDecision = null
                        onDecision(confirm)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(
                        if (confirm) "proposal_review_confirm_decision" else "proposal_review_reject_decision",
                    ),
                ) {
                    Text(stringResource(if (confirm) R.string.proposal_review_confirm else R.string.proposal_review_reject))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDecision = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.proposal_review_cancel))
                }
            },
        )
    }
}

@Composable
private fun ProposalReviewField(
    proposalKey: String,
    field: ProposalReviewFieldUi,
    relatedProposal: ProposalReviewCardUi?,
    sources: List<ProposalReviewSourceUi>,
    canEdit: Boolean,
    onSaveLocalEdit: (String) -> Unit,
) {
    var editing by rememberSaveable(proposalKey, field.pointer) { mutableStateOf(false) }
    var evidenceExpanded by rememberSaveable(proposalKey, field.pointer) { mutableStateOf(false) }
    var draft by rememberSaveable(proposalKey, field.pointer, field.value, field.localEdit) {
        mutableStateOf(field.localEdit ?: field.value)
    }
    val label = when {
        field.relatedProposalId != null -> stringResource(R.string.ux_proposal_related_field)
        field.key.toIntOrNull() != null -> stringResource(R.string.ux_proposal_array_item)
        else -> fieldLabel(field.key)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("proposal_review_field_${proposalKey}_${field.pointer}"),
    ) {
        Column(modifier = Modifier.padding(LocalVisualMetrics.current.panelPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (field.relatedProposalId != null) {
                val relatedLabel = relatedProposal?.let { "${it.title} · ${categoryLabelPlain(it.category)}" }
                    ?: field.relatedProposalId
                Text(
                    stringResource(R.string.proposal_review_relation_value, relatedLabel),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.proposal_review_edit_label, label)) },
                    modifier = Modifier.fillMaxWidth().testTag("proposal_review_editor_${proposalKey}_${field.pointer}"),
                    minLines = 1,
                    maxLines = 4,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            onSaveLocalEdit(draft)
                            editing = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("proposal_review_save_edit_${proposalKey}_${field.pointer}"),
                    ) { Text(stringResource(R.string.proposal_review_apply_local_edit)) }
                    TextButton(
                        onClick = {
                            draft = field.localEdit ?: field.value
                            editing = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.proposal_review_cancel)) }
                }
            } else {
                Text(
                    text = field.localEdit ?: field.value,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("proposal_review_value_${field.pointer}"),
                )
                if (field.localEdit != null) {
                    Text(
                        stringResource(R.string.proposal_review_local_edit_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("proposal_review_edit_not_persisted_${proposalKey}_${field.pointer}"),
                    )
                }
            }
            field.assessment?.let { assessment -> AssessmentLabels(assessment.basis, assessment.modality, assessment.polarity) }
            if (field.temporal != null) TemporalReviewDetail(field.temporal)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canEdit && !editing && field.relatedProposalId == null) {
                    TextButton(
                        onClick = {
                            draft = field.localEdit ?: field.value
                            editing = true
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("proposal_review_edit_${proposalKey}_${field.pointer}"),
                    ) {
                        YervisIcon(YervisSymbol.Edit, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                        Text(stringResource(R.string.proposal_review_edit_field))
                    }
                }
                if (field.evidence.isNotEmpty()) {
                    DisclosureButton(
                        expanded = evidenceExpanded,
                        onClick = { evidenceExpanded = !evidenceExpanded },
                        label = stringResource(if (evidenceExpanded) R.string.ux_proposal_hide_evidence else R.string.ux_proposal_show_evidence),
                        tag = "proposal_review_evidence_toggle_${proposalKey}_${field.pointer}",
                    )
                }
            }
            ReviewDisclosure(evidenceExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    field.evidence.forEach { evidence ->
                        key(evidence.sourceEntryId, evidence.startUtf16, evidence.endUtf16) {
                            ReviewEvidence(evidence, sources)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssessmentLabels(basis: String?, modality: String?, polarity: String?) {
    val labels = buildList {
        basis?.let { add(assessmentLabel(it)) }
        modality?.let { add(assessmentLabel(it)) }
        polarity?.let { add(assessmentLabel(it)) }
    }.distinct()
    if (labels.isNotEmpty()) {
        val description = stringResource(
            R.string.proposal_review_assessment_description,
            labels.joinToString(", "),
        )
        Text(
            text = labels.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = description }
                .testTag("proposal_review_assessment"),
        )
    }
}

@Composable
private fun TemporalReviewDetail(temporal: ProposalReviewTemporalUi) {
    val status = when (temporal.status) {
        TemporalResolutionStatus.RESOLVED -> stringResource(R.string.proposal_review_temporal_resolved)
        TemporalResolutionStatus.PARTIALLY_RESOLVED -> stringResource(R.string.proposal_review_temporal_partial)
        TemporalResolutionStatus.AMBIGUOUS -> stringResource(R.string.proposal_review_temporal_ambiguous)
        TemporalResolutionStatus.INVALID -> stringResource(R.string.proposal_review_temporal_invalid)
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("proposal_review_temporal_${temporal.expression}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            status,
            style = MaterialTheme.typography.labelLarge,
            color = if (temporal.status == TemporalResolutionStatus.RESOLVED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
        )
        if (temporal.status == TemporalResolutionStatus.RESOLVED) {
            Text(
                stringResource(
                    R.string.proposal_review_temporal_value,
                    listOfNotNull(temporal.localDate, temporal.localTime, temporal.dayPart).joinToString(" · ")
                        .ifBlank { temporal.expression },
                    temporal.timezoneId.orEmpty(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.ux_proposal_temporal_expression, temporal.expression),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.proposal_review_temporal_unresolved, temporal.expression),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                temporalReasonLabel(temporal.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val knownParts = remember(temporal) {
                listOfNotNull(temporal.localDate, temporal.localTime, temporal.dayPart, temporal.timezoneId)
                    .joinToString(" · ")
            }
            if (knownParts.isNotBlank()) {
                Text(
                    stringResource(R.string.ux_proposal_temporal_known, knownParts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val endParts = remember(temporal.endLocalDate, temporal.endLocalTime) {
            listOfNotNull(temporal.endLocalDate, temporal.endLocalTime).joinToString(" · ")
        }
        if (endParts.isNotBlank()) {
            Text(
                stringResource(R.string.ux_proposal_temporal_end, endParts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.proposal_review_temporal_capture_separate),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReviewEvidence(evidence: ProposalReviewEvidenceUi, sources: List<ProposalReviewSourceUi>) {
    val source = remember(evidence.sourceEntryId, sources) {
        sources.firstOrNull { it.sourceEntryId == evidence.sourceEntryId }
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().testTag("proposal_review_evidence_${evidence.startUtf16}_${evidence.endUtf16}"),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.proposal_review_evidence_heading), style = MaterialTheme.typography.labelLarge)
            Text("“${evidence.quote}”", style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    R.string.proposal_review_evidence_source,
                    evidence.sourceEntryId,
                    evidence.revisionLabel,
                    evidence.startUtf16,
                    evidence.endUtf16,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (source != null) {
                Text(
                    stringResource(R.string.proposal_review_evidence_matches_version, source.revisionLabel),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ReviewSource(source: ProposalReviewSourceUi) {
    val captureTime = remember(source.capturedAtEpochMillis, source.timezoneId) { capturedTime(source) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("proposal_review_source_${source.sourceEntryId}"),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(source.sourceEntryId, style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.proposal_review_capture_metadata, source.revisionLabel, captureTime, source.timezoneId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (source.originalText != source.reviewedText) {
                Text(stringResource(R.string.proposal_review_original_text), style = MaterialTheme.typography.labelMedium)
                Text(
                    source.originalText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("proposal_review_original_${source.sourceEntryId}"),
                )
                Text(stringResource(R.string.proposal_review_analyzed_revision), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                source.reviewedText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("proposal_review_reviewed_${source.sourceEntryId}"),
            )
        }
    }
}

@Composable
private fun DisclosureButton(expanded: Boolean, onClick: () -> Unit, label: String, tag: String) {
    val expansionState = stringResource(if (expanded) R.string.ux_proposal_expanded else R.string.ux_proposal_collapsed)
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = expansionState }.testTag(tag),
    ) {
        Text(label, modifier = Modifier.weight(1f, fill = false))
        YervisIcon(if (expanded) YervisSymbol.ExpandLess else YervisSymbol.ExpandMore, modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}

@Composable
private fun ReviewDisclosure(expanded: Boolean, content: @Composable () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val duration = motionDurationMillis(180, reduceMotion)
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(duration)) + fadeIn(tween(duration)),
        exit = shrinkVertically(tween(duration)) + fadeOut(tween(duration)),
    ) { content() }
}

private fun categorySymbol(category: String): YervisSymbol = when (category) {
    "TASK" -> YervisSymbol.Task
    "EVENT" -> YervisSymbol.Calendar
    "ROUTINE" -> YervisSymbol.Routine
    "HABIT" -> YervisSymbol.Habit
    "REMINDER" -> YervisSymbol.Reminder
    "IDEA" -> YervisSymbol.Idea
    "FACT" -> YervisSymbol.Fact
    "DECISION" -> YervisSymbol.Decision
    "GOAL" -> YervisSymbol.Goal
    "PERSON" -> YervisSymbol.Person
    "PROJECT" -> YervisSymbol.Project
    else -> YervisSymbol.Info
}

@Composable
private fun ReviewMessage(title: String, tag: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun categoryLabel(category: String): String = stringResource(
    when (category) {
        "TASK" -> R.string.proposal_category_task
        "EVENT" -> R.string.proposal_category_event
        "ROUTINE" -> R.string.proposal_category_routine
        "HABIT" -> R.string.proposal_category_habit
        "REMINDER" -> R.string.proposal_category_reminder
        "IDEA" -> R.string.proposal_category_idea
        "FACT" -> R.string.proposal_category_fact
        "DECISION" -> R.string.proposal_category_decision
        "GOAL" -> R.string.proposal_category_goal
        "PERSON" -> R.string.proposal_category_person
        "PROJECT" -> R.string.proposal_category_project
        else -> R.string.proposal_review_unknown_category
    },
)

@Composable
private fun categoryLabelPlain(category: String): String = when (category) {
    "TASK" -> stringResource(R.string.proposal_category_task)
    "EVENT" -> stringResource(R.string.proposal_category_event)
    "ROUTINE" -> stringResource(R.string.proposal_category_routine)
    "HABIT" -> stringResource(R.string.proposal_category_habit)
    "REMINDER" -> stringResource(R.string.proposal_category_reminder)
    "IDEA" -> stringResource(R.string.proposal_category_idea)
    "FACT" -> stringResource(R.string.proposal_category_fact)
    "DECISION" -> stringResource(R.string.proposal_category_decision)
    "GOAL" -> stringResource(R.string.proposal_category_goal)
    "PERSON" -> stringResource(R.string.proposal_category_person)
    "PROJECT" -> stringResource(R.string.proposal_category_project)
    else -> stringResource(R.string.proposal_review_unknown_category)
}

@Composable
private fun fieldLabel(key: String): String = when (key) {
    "title" -> stringResource(R.string.proposal_field_title)
    "state" -> stringResource(R.string.proposal_field_state)
    "startsAt" -> stringResource(R.string.proposal_field_starts_at)
    "endsAt" -> stringResource(R.string.proposal_field_ends_at)
    "occurredAt" -> stringResource(R.string.proposal_field_occurred_at)
    "dueAt" -> stringResource(R.string.proposal_field_due_at)
    "plannedAt" -> stringResource(R.string.proposal_field_planned_at)
    "frequency" -> stringResource(R.string.proposal_field_frequency)
    "daysOfWeek" -> stringResource(R.string.proposal_field_days)
    "patternKind" -> stringResource(R.string.proposal_field_pattern_kind)
    "exceptions" -> stringResource(R.string.proposal_field_exceptions)
    "person" -> stringResource(R.string.proposal_field_person)
    "project" -> stringResource(R.string.proposal_field_project)
    "participants" -> stringResource(R.string.proposal_field_participants)
    "location" -> stringResource(R.string.proposal_field_location)
    "duration" -> stringResource(R.string.proposal_field_duration)
    "priority" -> stringResource(R.string.proposal_field_priority)
    "reason" -> stringResource(R.string.proposal_field_reason)
    "at" -> stringResource(R.string.proposal_field_alarm_time)
    "expression" -> stringResource(R.string.proposal_field_temporal_expression)
    else -> stringResource(R.string.proposal_field_generic, key)
}

@Composable
private fun reviewStateLabel(state: ProposalReviewState): String = when (state) {
    ProposalReviewState.CANDIDATE -> stringResource(R.string.proposal_status_candidate)
    ProposalReviewState.PROPOSED -> stringResource(R.string.proposal_status_pending)
    ProposalReviewState.CONFIRMED -> stringResource(R.string.proposal_status_confirmed_demo)
    ProposalReviewState.REJECTED -> stringResource(R.string.proposal_status_rejected_demo)
    ProposalReviewState.EXPIRED -> stringResource(R.string.proposal_status_expired_demo)
}

@Composable
private fun assessmentLabel(value: String): String = when (value) {
    "EXPLICIT" -> stringResource(R.string.proposal_assessment_explicit)
    "INFERRED" -> stringResource(R.string.proposal_assessment_inferred)
    "ASSERTED" -> stringResource(R.string.proposal_assessment_asserted)
    "HYPOTHETICAL", "POSSIBLE" -> stringResource(R.string.proposal_assessment_hypothetical)
    "NEGATED" -> stringResource(R.string.proposal_assessment_negated)
    "AFFIRMED" -> stringResource(R.string.proposal_assessment_affirmed)
    else -> stringResource(R.string.proposal_assessment_generic, value)
}

@Composable
private fun temporalReasonLabel(reason: com.yezvi.assistant.domain.intelligence.TemporalResolutionReason): String = when (reason) {
    com.yezvi.assistant.domain.intelligence.TemporalResolutionReason.NEXT_WEEKDAY_NEEDS_POLICY,
    com.yezvi.assistant.domain.intelligence.TemporalResolutionReason.WEEKDAY_NEEDS_REFERENCE -> stringResource(R.string.proposal_review_temporal_reason_weekday)
    com.yezvi.assistant.domain.intelligence.TemporalResolutionReason.AMBIGUOUS_HOUR -> stringResource(R.string.proposal_review_temporal_reason_hour)
    com.yezvi.assistant.domain.intelligence.TemporalResolutionReason.DAY_PART_HAS_NO_CLOCK_TIME -> stringResource(R.string.proposal_review_temporal_reason_day_part)
    else -> stringResource(R.string.proposal_review_temporal_reason_review)
}

private fun capturedTime(source: ProposalReviewSourceUi): String = runCatching {
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.forLanguageTag("es-PE")).apply {
        timeZone = TimeZone.getTimeZone(source.timezoneId)
    }.format(Date(source.capturedAtEpochMillis))
}.getOrElse { source.capturedAtEpochMillis.toString() }
