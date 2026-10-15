package com.yezvi.assistant.data.intelligence.deduplication

import com.yezvi.assistant.data.intelligence.validation.*
import com.yezvi.assistant.data.local.memory.InformationProposalRelationEntity
import com.yezvi.assistant.domain.intelligence.*

internal val directedKinds = setOf(ProposalComparisonKind.UPDATE_PROPOSED,
    ProposalComparisonKind.EXCEPTION_PROPOSED, ProposalComparisonKind.RESCHEDULE_PROPOSED)

internal fun relationEntity(
    previous: ProposalVersionReference,
    incoming: ProposalVersionReference,
    kind: ProposalComparisonKind,
    previousDescriptor: String,
    incomingDescriptor: String,
    at: Long,
): InformationProposalRelationEntity {
    fun ref(value: ProposalVersionReference) = JsonObject(mapOf(
        "extractionId" to JsonString(value.identity.extractionId),
        "proposalId" to JsonString(value.identity.proposalId),
        "version" to JsonNumber(value.version.toBigDecimal()),
    ))
    var from = previous
    var to = incoming
    var descriptors = listOf(previousDescriptor, incomingDescriptor)
    if (kind !in directedKinds && canonicalJson(ref(from)) > canonicalJson(ref(to))) {
        from = incoming; to = previous; descriptors = descriptors.reversed()
    }
    val evidence = proposalDigest(canonicalJson(JsonObject(mapOf(
        "fromDescriptor" to JsonString(descriptors[0]), "toDescriptor" to JsonString(descriptors[1]),
    ))))
    val id = proposalDigest(canonicalJson(JsonObject(mapOf(
        "from" to ref(from), "to" to ref(to), "kind" to JsonString(kind.name),
        "comparatorVersion" to JsonNumber(ConservativeProposalComparator.VERSION.toBigDecimal()),
        "evidenceDigest" to JsonString(evidence),
    ))))
    return InformationProposalRelationEntity(id, from.identity.extractionId, from.identity.proposalId, from.version,
        to.identity.extractionId, to.identity.proposalId, to.version, kind.name,
        ConservativeProposalComparator.VERSION, evidence, at)
}
