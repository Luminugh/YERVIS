package com.yezvi.assistant.data.speech

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Checks the fixed NeMo token/BPE resources and safe per-stream hotword input.
 *
 * This is a conservative lexical check, not a Kotlin tokenizer. sherpa-onnx 1.13.8
 * encodes each whitespace-separated word with simple-sentencepiece 0.7, preserving
 * case and adding ▁. Its UTF-8 byte DAG and score/tie rules choose the segmentation.
 * The derived -1.0 vocabulary does not retain the original SentencePiece Unigram
 * scores or normalizer. Native segmentation and acoustic benefit need separate evidence.
 * No text returned by the decoder is rewritten here.
 */
internal class LocalSpeechVocabulary private constructor(
    val tokenCount: Int,
    private val singleCodePoints: Set<Int>,
) {
    internal enum class RejectionReason {
        EMPTY,
        RESERVED_DELIMITER,
        UNSAFE_CHARACTER,
        UNREPRESENTABLE_CHARACTER,
    }

    internal data class RejectedTerm(val term: String, val reason: RejectionReason)

    internal data class Validation(
        val acceptedTerms: List<String>,
        val rejectedTerms: List<RejectedTerm>,
    ) {
        val hotwords: String get() = acceptedTerms.joinToString("/")
    }

    /** Keeps spelling/case intact; only trims and collapses ordinary ASCII spaces. */
    fun validateTerms(terms: List<String>): Validation {
        val accepted = linkedSetOf<String>()
        val rejected = mutableListOf<RejectedTerm>()
        for (term in terms) {
            val reason = rejectionReason(term)
            if (reason != null) {
                rejected += RejectedTerm(term, reason)
            } else {
                accepted += term.trim(' ').split(' ').filter(String::isNotEmpty).joinToString(" ")
            }
        }
        return Validation(accepted.toList(), rejected.toList())
    }

    private fun rejectionReason(term: String): RejectionReason? {
        if (term.all { it == ' ' }) return RejectionReason.EMPTY
        if ('/' in term || ':' in term) return RejectionReason.RESERVED_DELIMITER
        val points = codePoints(term)
        // Newlines delimit phrases natively. Reject other whitespace/control/format
        // characters instead of assuming Java and the C++ locale normalize them alike.
        if (points.any { point ->
                point != ' '.code && (Character.isWhitespace(point) ||
                    Character.isSpaceChar(point) || Character.isISOControl(point) ||
                    Character.getType(point) == Character.FORMAT.toInt() ||
                    point == WORD_BOUNDARY.code || point in 0xD800..0xDFFF)
            }) return RejectionReason.UNSAFE_CHARACTER
        // Every non-space codepoint needs its own token. Multi-character-only
        // coverage is deliberately rejected; this does not predict native pieces.
        if (points.any { it != ' '.code && it !in singleCodePoints }) {
            return RejectionReason.UNREPRESENTABLE_CHARACTER
        }
        return null
    }

    companion object {
        private const val WORD_BOUNDARY = '▁'
        private const val MODEL_TOKEN_COUNT = 1025
        private val TOKEN_ROW = Regex("(\\S+) ([0-9]+)")

        /** Caller performs file IO on its IO dispatcher; this helper is synchronous. */
        fun fromFiles(
            tokensFile: File,
            bpeFile: File,
            expectedTokenCount: Int = MODEL_TOKEN_COUNT,
        ): LocalSpeechVocabulary = fromTexts(
            readUtf8(tokensFile), readUtf8(bpeFile), expectedTokenCount,
        )

        /** Requires the prepared UTF-8/LF format and exact token ordering/scores. */
        fun fromTexts(
            tokensText: String,
            bpeText: String,
            expectedTokenCount: Int = MODEL_TOKEN_COUNT,
        ): LocalSpeechVocabulary {
            require(expectedTokenCount >= 3)
            val tokenRows = rows(tokensText)
            val bpeRows = rows(bpeText)
            if (tokenRows.size != expectedTokenCount || bpeRows.size != expectedTokenCount) {
                throw IOException("Unexpected local speech vocabulary size")
            }
            val pieces = tokenRows.mapIndexed { index, row ->
                val match = TOKEN_ROW.matchEntire(row)
                    ?: throw IOException("Invalid local speech token row")
                val piece = match.groupValues[1]
                if (match.groupValues[2] != index.toString() || bpeRows[index] != "$piece\t-1.0") {
                    throw IOException("Local speech vocabulary IDs, pieces or scores differ")
                }
                piece
            }
            if (pieces.toSet().size != pieces.size || pieces.first() != "<unk>" ||
                pieces.last() != "<blk>" || WORD_BOUNDARY.toString() !in pieces) {
                throw IOException("Invalid local speech vocabulary symbols")
            }
            val singlePoints = pieces.mapNotNull { piece ->
                codePoints(piece).singleOrNull()
            }.toSet()
            return LocalSpeechVocabulary(pieces.size, singlePoints)
        }

        private fun rows(text: String): List<String> {
            if (!text.endsWith('\n') || '\r' in text || '\uFEFF' in text) {
                throw IOException("Local speech vocabulary must use UTF-8 without BOM and LF rows")
            }
            return text.dropLast(1).split('\n')
        }

        private fun readUtf8(file: File): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(file.readBytes())).toString()
        } catch (error: CharacterCodingException) {
            throw IOException("Invalid UTF-8 local speech vocabulary", error)
        }

        private fun codePoints(text: String): List<Int> = buildList {
            var offset = 0
            while (offset < text.length) {
                val point = Character.codePointAt(text, offset)
                add(point)
                offset += Character.charCount(point)
            }
        }
    }
}
