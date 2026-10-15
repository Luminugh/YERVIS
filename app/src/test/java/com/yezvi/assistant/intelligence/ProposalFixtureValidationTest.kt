package com.yezvi.assistant.intelligence

import com.yezvi.assistant.data.intelligence.validation.*
import com.yezvi.assistant.domain.intelligence.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Every canonical case is a separately reported JVM test, not a rewritten set of expectations. */
@RunWith(Parameterized::class)
class ProposalFixtureValidationTest(private val file: String, private val valid: Boolean, private val code: ProposalRejectionCode?) {
    @Test fun canonicalCase() {
        // Paths in the immutable manifest already start at v1; never treat them as production input.
        val payload = File(F402Fixtures.root, file).readText(Charsets.UTF_8)
        val id = BoundedJson(payload).parse().obj().s("requestId")
        val request = F402Fixtures.cases.single { it.request.requestId == id }.request
        val result = StrictInformationProposalValidator().validateJson(request, payload)
        if (valid) {
            assertTrue("$file: $result", result is ProposalValidationResult.Valid)
            result as ProposalValidationResult.Valid
            assertTrue(result.document.requiresHumanReview)
            assertEquals(payload, result.document.json)
            assertTrue(ProposalReviewRequirement.HUMAN_MEANING_REVIEW in result.reviewRequirements)
        } else {
            assertTrue("$file: $result", result is ProposalValidationResult.Rejected)
            assertEquals(file, code, (result as ProposalValidationResult.Rejected).error.code)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun fixtures(): Collection<Array<Any?>> {
            val manifest = BoundedJson(File(F402Fixtures.root, "fixtures/manifest.json").readText()).parse().obj()
            val semantic = listOf(ProposalRejectionCode.EVIDENCE_QUOTE, ProposalRejectionCode.EVIDENCE_RANGE,
                ProposalRejectionCode.INVALID_REFERENCE, ProposalRejectionCode.SNAPSHOT_MISMATCH, ProposalRejectionCode.DUPLICATE_ID,
                ProposalRejectionCode.DUPLICATE_RECURRENCE, ProposalRejectionCode.TEMPORAL_INCONSISTENCY,
                ProposalRejectionCode.SOURCE_NOT_AUTHORIZED, ProposalRejectionCode.INT64_RANGE, ProposalRejectionCode.EVIDENCE_QUOTE)
            return manifest.a("cases").map { node ->
                val entry = node.obj()
                val name = entry.s("file")
                val valid = (entry["expectedContractValid"] as JsonBoolean).value
                val code = when {
                    valid -> null
                    "/k" in name -> semantic[name.substringAfterLast('/').substring(1, 3).toInt() - 1]
                    "s01-" in name -> ProposalRejectionCode.UNSUPPORTED_VERSION
                    else -> ProposalRejectionCode.STRUCTURE
                }
                arrayOf(name, valid, code)
            }
        }
    }
}
