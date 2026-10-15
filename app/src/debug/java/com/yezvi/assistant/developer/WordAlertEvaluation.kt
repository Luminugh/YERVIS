package com.yezvi.assistant.developer

import java.text.Normalizer
import java.util.Locale

/** Evaluates already-selected alerts. A reference never selects or changes an alert. */
internal object WordAlertEvaluation {
    data class Stats(
        val alerts: Int,
        val trueAlerts: Int,
        val falseAlerts: Int,
        val erroneousEmittedWords: Int,
        val omissions: Int,
        val precision: Double?,
        val detectedErrorFraction: Double?,
        val markedWordFraction: Double?,
        val unavailableReason: String? = null,
    )

    fun evaluate(reference: String, analysis: WordUncertaintyAnalysis): Stats {
        fun unavailable(reason: String) = Stats(0, 0, 0, 0, 0, null, null, null, reason)
        if (!analysis.hasValidatedThreshold || analysis.unavailableReason != null) {
            return unavailable("Se necesita un umbral validado y un análisis disponible")
        }
        if (reference.length > MAX_TEXT_CHARS || analysis.text.length > MAX_TEXT_CHARS) {
            return unavailable("Texto demasiado largo para evaluar alertas")
        }
        if (analysis.reviewWordIndices.any { it !in analysis.words.indices }) {
            return unavailable("Alertas no alineadas con las palabras")
        }
        val expected = tokenize(reference)
        if (expected.isEmpty()) return unavailable("Falta una referencia exacta con palabras")
        val actual = ArrayList<String>()
        val wordIndices = ArrayList<Int>()
        analysis.words.forEachIndexed { wordIndex, word ->
            tokenize(word.text).forEach { token -> actual += token; wordIndices += wordIndex }
        }
        if (actual != tokenize(analysis.text)) return unavailable("Palabras no alineadas con el texto final")
        val columns = actual.size + 1
        val cells = (expected.size + 1L) * columns
        if (cells > MAX_ALIGNMENT_CELLS) return unavailable("Alineación demasiado grande para evaluar alertas")
        val costs = IntArray(cells.toInt())
        for (i in 0..expected.size) costs[i * columns] = i
        for (j in 0..actual.size) costs[j] = j
        for (i in 1..expected.size) for (j in 1..actual.size) {
            costs[i * columns + j] = minOf(
                costs[(i - 1) * columns + j - 1] + if (expected[i - 1] == actual[j - 1]) 0 else 1,
                costs[(i - 1) * columns + j] + 1,
                costs[i * columns + j - 1] + 1,
            )
        }
        var i = expected.size
        var j = actual.size
        var omissions = 0
        val erroneousWords = HashSet<Int>()
        while (i > 0 || j > 0) {
            val current = costs[i * columns + j]
            if (i > 0 && j > 0) {
                val equal = expected[i - 1] == actual[j - 1]
                if (current == costs[(i - 1) * columns + j - 1] + if (equal) 0 else 1) {
                    if (!equal) erroneousWords += wordIndices[j - 1]
                    i--; j--
                    continue
                }
            }
            if (i > 0 && current == costs[(i - 1) * columns + j] + 1) {
                omissions++; i--
            } else {
                erroneousWords += wordIndices[j - 1]
                j--
            }
        }
        val alerts = analysis.reviewWordIndices.size
        val correctAlerts = analysis.reviewWordIndices.count { it in erroneousWords }
        return Stats(
            alerts = alerts,
            trueAlerts = correctAlerts,
            falseAlerts = alerts - correctAlerts,
            erroneousEmittedWords = erroneousWords.size,
            omissions = omissions,
            precision = if (alerts > 0) correctAlerts.toDouble() / alerts else null,
            detectedErrorFraction = if (erroneousWords.isNotEmpty()) correctAlerts.toDouble() / erroneousWords.size else null,
            markedWordFraction = if (analysis.words.isNotEmpty()) alerts.toDouble() / analysis.words.size else null,
        )
    }

    private fun tokenize(text: String): List<String> {
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val words = StringBuilder(normalized.length)
        var offset = 0
        while (offset < normalized.length) {
            val codePoint = Character.codePointAt(normalized, offset)
            val type = Character.getType(codePoint)
            when {
                type == Character.NON_SPACING_MARK.toInt() -> Unit
                Character.isLetterOrDigit(codePoint) || type == Character.LETTER_NUMBER.toInt() ||
                    type == Character.OTHER_NUMBER.toInt() || Character.isWhitespace(codePoint) -> words.appendCodePoint(codePoint)
                else -> words.append(' ')
            }
            offset += Character.charCount(codePoint)
        }
        return words.toString().trim().split(WHITESPACE).filter(String::isNotEmpty)
    }

    private const val MAX_TEXT_CHARS = 20_000
    private const val MAX_ALIGNMENT_CELLS = 250_000
    private val WHITESPACE = Regex("\\s+")
}
