package com.yezvi.assistant.data.intelligence.validation

import com.yezvi.assistant.domain.intelligence.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** No extractor, repository or effects. Invoke explicitly on a worker thread after extraction. */
class StrictInformationProposalValidator : InformationProposalValidator {
    override fun validate(request: InformationExtractionRequest, document: UnvalidatedProposalDocument): ProposalValidationResult {
        val result = validateJson(request, document.json)
        if (result is ProposalValidationResult.Valid && (document.schemaVersion != request.schemaVersion || document.outcome != result.document.outcome)) {
            return rejected(ProposalRejectionCode.DOCUMENT_METADATA)
        }
        return result
    }

    /** Future byte transports can validate UTF-8 before creating an untrusted document. No replacement decoding. */
    fun validateUtf8(request: InformationExtractionRequest, bytes: ByteArray): ProposalValidationResult {
        if (bytes.size > BoundedJson.MAX_BYTES) return rejected(ProposalRejectionCode.PAYLOAD_LIMIT)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) { return rejected(ProposalRejectionCode.INVALID_UNICODE) }
        return validateJson(request, text)
    }

    fun validateJson(request: InformationExtractionRequest, json: String): ProposalValidationResult = try {
        val tree = BoundedJson(json).parse()
        if (request.schemaVersion != InformationProposalSchema.VERSION ||
            (tree as? JsonObject)?.get("schemaVersion")?.let { it != JsonString(InformationProposalSchema.VERSION) } == true) {
            throw ValidationAbort(ProposalRejectionCode.UNSUPPORTED_VERSION, "/schemaVersion")
        }
        val schema = try { CanonicalProposalSchema.instance } catch (_: IllegalStateException) {
            throw ValidationAbort(ProposalRejectionCode.SCHEMA_CONFIGURATION)
        }
        schema.violation(tree)?.let { throw ValidationAbort(ProposalRejectionCode.STRUCTURE, it.path) }
        val root = tree.obj()
        val review = ProposalSemanticChecks(request).check(root)
        ProposalValidationResult.Valid(ValidatedProposalDocument(json, ExtractionOutcome.valueOf(root.s("outcome")), root.a("proposals").size),
            Collections.unmodifiableSet(review))
    } catch (failure: ValidationAbort) {
        ProposalValidationResult.Rejected(ProposalValidationError(failure.code, failure.path, failure.offset))
    }

    private fun rejected(code: ProposalRejectionCode) = ProposalValidationResult.Rejected(ProposalValidationError(code, ""))
}
