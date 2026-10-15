package com.yezvi.assistant.developer

/** Debug-only scores, not calibrated probabilities that a word is correct. */
internal data class WordUncertaintyWord(
    val text: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val minimumTokenLogProbability: Float,
    val firstTokenTimestampSeconds: Float,
)

/** An offline calibration artifact is usable only for its exact runtime/model/decoder profile. */
internal data class WordReviewThreshold(
    val profileId: String,
    val maximumLogProbability: Float,
    val adjustmentPrecision: Double,
    val adjustmentMarkedFraction: Double,
    val evaluationPrecision: Double,
    val evaluationAlerts: Int,
    val evaluationDetectableErrors: Int,
) {
    fun matches(profile: String): Boolean = profileId.isNotBlank() && profileId == profile &&
        maximumLogProbability.isFinite() && maximumLogProbability <= 0f &&
        adjustmentPrecision.isFinite() && adjustmentPrecision in 0.85..1.0 &&
        adjustmentMarkedFraction.isFinite() && adjustmentMarkedFraction in 0.0..0.15 &&
        evaluationPrecision.isFinite() && evaluationPrecision in 0.8..1.0 && evaluationAlerts >= 30 &&
        evaluationDetectableErrors >= 50
}

internal data class WordUncertaintyAnalysis(
    val text: String,
    val words: List<WordUncertaintyWord>,
    val tokenCount: Int,
    val unavailableReason: String? = null,
    val reviewWordIndices: Set<Int> = emptySet(),
    val hasValidatedThreshold: Boolean = false,
)

/**
 * One bounded session accumulator. It never retains tokens, native buffers, or PCM. Calls may come
 * from a worker; a cancelled UI discards this whole accumulator instead of reusing it for a session.
 */
internal class WordUncertaintySession(
    private val profileId: String,
    private val threshold: WordReviewThreshold? = null,
) {
    private var sessionId: Long? = null
    private var text = ""
    private var tokenCount = 0
    private val words = ArrayList<WordUncertaintyWord>()
    private var failure: String? = null

    @Synchronized
    fun append(
        sessionId: Long,
        textBefore: String,
        segmentText: String,
        tokens: List<String>,
        timestamps: List<Float>,
        tokenLogProbabilities: FloatArray?,
        audioDurationMs: Double,
    ): WordUncertaintyAnalysis {
        failure?.let { return unavailable(it) }
        if ((this.sessionId != null && this.sessionId != sessionId) || textBefore != text) {
            return fail("La secuencia de segmentos cambió; análisis descartado")
        }
        this.sessionId = sessionId
        if (tokens.size > MAX_TOKENS - tokenCount) return fail("Límite de 2.048 tokens alcanzado; análisis desactivado")
        val segment = segmentText.trim()
        val separator = if (text.isEmpty() || segment.isEmpty()) "" else " "
        val offset = text.length + separator.length
        val nextText = text + separator + segment
        val local = analyzeSegment(segment, tokens, timestamps, tokenLogProbabilities, audioDurationMs)
        local.unavailableReason?.let { return fail(it) }
        if (local.words.size > MAX_WORDS - words.size) return fail("Límite de 512 palabras alcanzado; análisis desactivado")
        tokenCount += tokens.size
        text = nextText
        local.words.forEach { word ->
            words += word.copy(startUtf16 = word.startUtf16 + offset, endUtf16 = word.endUtf16 + offset)
        }
        val usableThreshold = threshold?.takeIf { it.matches(profileId) }
        return WordUncertaintyAnalysis(
            text = text,
            words = words.toList(),
            tokenCount = tokenCount,
            reviewWordIndices = if (usableThreshold == null) emptySet() else words.indices.filterTo(linkedSetOf()) {
                words[it].minimumTokenLogProbability <= usableThreshold.maximumLogProbability
            },
            hasValidatedThreshold = usableThreshold != null,
        )
    }

    private fun fail(reason: String): WordUncertaintyAnalysis {
        failure = reason
        words.clear()
        return unavailable(reason)
    }

    private fun unavailable(reason: String) = WordUncertaintyAnalysis(text, emptyList(), tokenCount, reason)

    companion object {
        const val MAX_TOKENS = 2_048
        const val MAX_WORDS = 512

        private data class Piece(val start: Int, val end: Int, val index: Int, val evaluable: Boolean)

        internal fun analyzeSegment(
            text: String,
            tokens: List<String>,
            timestamps: List<Float>,
            scores: FloatArray?,
            audioDurationMs: Double,
        ): WordUncertaintyAnalysis {
            fun unavailable(reason: String) = WordUncertaintyAnalysis(text, emptyList(), tokens.size, reason)
            if (tokens.size > MAX_TOKENS) return unavailable("Demasiados tokens para analizar")
            if (scores == null) return unavailable("El runtime no entregó scores compatibles")
            if (tokens.size != scores.size || tokens.size != timestamps.size) {
                return unavailable("Tokens, tiempos y scores no están alineados")
            }
            if (!audioDurationMs.isFinite() || audioDurationMs < 0 ||
                scores.any { !it.isFinite() || it > 0f } ||
                timestamps.indices.any { index ->
                    val time = timestamps[index]
                    !time.isFinite() || time < 0f || time > audioDurationMs / 1_000 + 0.1 ||
                        index > 0 && time < timestamps[index - 1]
                }) {
                return unavailable("Scores o tiempos inválidos")
            }
            // Sherpa's official JNI result already converts ▁ to ASCII spaces. Tests and native
            // vocabulary tools can still supply ▁; both expand to the same exact source text.
            if (tokens.any { token -> token.isEmpty() || token.any { it != ' ' && it.isWhitespace() } ||
                    token == "<unk>" || token == "<blk>" || token == "<s>" || token == "</s>" }) {
                return unavailable("Piezas BPE no compatibles")
            }
            val reconstructed = StringBuilder()
            val pieces = ArrayList<Piece>(tokens.size)
            tokens.forEachIndexed { index, token ->
                val start = reconstructed.length
                reconstructed.append(token.replace('▁', ' '))
                pieces += Piece(start, reconstructed.length, index, hasLetterOrDigit(token, 0, token.length))
            }
            val reconstructedText = reconstructed.toString()
            val trimStart = reconstructedText.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) reconstructedText.length else it }
            if (reconstructedText.trim() != text) return unavailable("Las piezas BPE no reconstruyen el texto exacto")
            val result = ArrayList<WordUncertaintyWord>()
            var offset = 0
            var pieceCursor = 0
            while (offset < text.length) {
                var codePoint = Character.codePointAt(text, offset)
                if (!isLexical(codePoint)) { offset += Character.charCount(codePoint); continue }
                val start = offset
                while (offset < text.length) {
                    codePoint = Character.codePointAt(text, offset)
                    if (!isLexical(codePoint)) break
                    offset += Character.charCount(codePoint)
                }
                val end = offset
                if (!hasLetterOrDigit(text, start, end)) {
                    return unavailable("Fragmento de marcas Unicode sin una palabra evaluable")
                }
                var minimum = Float.POSITIVE_INFINITY
                var firstTime = Float.NaN
                while (pieceCursor < pieces.size && pieces[pieceCursor].end <= start + trimStart) pieceCursor++
                var pieceIndex = pieceCursor
                while (pieceIndex < pieces.size && pieces[pieceIndex].start < end + trimStart) {
                    val piece = pieces[pieceIndex++]
                    val overlapStart = maxOf(start + trimStart, piece.start)
                    val overlapEnd = minOf(end + trimStart, piece.end)
                    // Standalone punctuation and boundary pieces do not lower a word's score.
                    if (overlapStart < overlapEnd && piece.evaluable) {
                        minimum = minOf(minimum, scores[piece.index])
                        if (firstTime.isNaN()) firstTime = timestamps[piece.index]
                    }
                }
                if (!minimum.isFinite() || !firstTime.isFinite()) return unavailable("Palabra sin piezas evaluables")
                result += WordUncertaintyWord(text.substring(start, end), start, end, minimum, firstTime)
                if (result.size > MAX_WORDS) return unavailable("Demasiadas palabras para analizar")
            }
            return WordUncertaintyAnalysis(text, result, tokens.size)
        }

        private fun hasLetterOrDigit(text: String, start: Int, end: Int): Boolean {
            var offset = start
            while (offset < end) {
                val codePoint = Character.codePointAt(text, offset)
                if (isLetterOrNumber(codePoint)) return true
                offset += Character.charCount(codePoint)
            }
            return false
        }

        private fun isLexical(codePoint: Int): Boolean = isLetterOrNumber(codePoint) ||
            Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt()

        private fun isLetterOrNumber(codePoint: Int): Boolean = Character.isLetterOrDigit(codePoint) ||
            Character.getType(codePoint) == Character.LETTER_NUMBER.toInt() ||
            Character.getType(codePoint) == Character.OTHER_NUMBER.toInt()
    }
}
