package com.yezvi.assistant.developer

import java.text.Normalizer
import java.util.Locale

/** Word-level WER matching the local accuracy script: case, punctuation and diacritics are ignored. */
internal object WordErrorScorer {
    private const val MAX_TEXT_CHARS = 20_000
    private const val MAX_ALIGNMENT_CELLS = 250_000L

    data class Stats(
        val referenceWords: Int,
        val hypothesisWords: Int,
        val correctWords: Int,
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val werPercent: Double?,
        val unavailableReason: String? = null,
        val alignmentAvailable: Boolean = true,
    ) {
        val wordErrors: Int get() = substitutions + deletions + insertions
    }

    fun score(reference: String, hypothesis: String): Stats {
        if (reference.length > MAX_TEXT_CHARS || hypothesis.length > MAX_TEXT_CHARS) {
            return unavailable("Texto demasiado largo para comparar de forma segura")
        }
        val expected = tokenize(reference)
        val actual = tokenize(hypothesis)
        val rows = expected.size + 1
        val columns = actual.size + 1
        val cellCount = rows.toLong() * columns.toLong()
        if (cellCount > MAX_ALIGNMENT_CELLS) {
            return Stats(expected.size, actual.size, 0, 0, 0, 0, null,
                "Alineación demasiado grande para comparar de forma segura", alignmentAvailable = false)
        }
        if (expected.isEmpty()) {
            return Stats(0, actual.size, 0, 0, 0, actual.size, null,
                "WER no definido sin palabras de referencia")
        }

        val costs = IntArray(cellCount.toInt())
        for (i in 0 until rows) costs[index(i, 0, columns)] = i
        for (j in 0 until columns) costs[index(0, j, columns)] = j
        for (i in 1 until rows) {
            for (j in 1 until columns) {
                val diagonal = costs[index(i - 1, j - 1, columns)] +
                    if (expected[i - 1] == actual[j - 1]) 0 else 1
                val deletion = costs[index(i - 1, j, columns)] + 1
                val insertion = costs[index(i, j - 1, columns)] + 1
                costs[index(i, j, columns)] = minOf(diagonal, deletion, insertion)
            }
        }

        var i = expected.size
        var j = actual.size
        var correct = 0
        var substitutions = 0
        var deletions = 0
        var insertions = 0
        while (i > 0 || j > 0) {
            val current = costs[index(i, j, columns)]
            if (i > 0 && j > 0) {
                val equal = expected[i - 1] == actual[j - 1]
                val diagonalCost = costs[index(i - 1, j - 1, columns)] + if (equal) 0 else 1
                if (current == diagonalCost) {
                    if (equal) correct++ else substitutions++
                    i--
                    j--
                    continue
                }
            }
            if (i > 0 && current == costs[index(i - 1, j, columns)] + 1) {
                deletions++
                i--
            } else {
                insertions++
                j--
            }
        }
        val errors = substitutions + deletions + insertions
        return Stats(expected.size, actual.size, correct, substitutions, deletions, insertions,
            errors * 100.0 / expected.size)
    }

    private fun unavailable(reason: String) = Stats(-1, -1, 0, 0, 0, 0, null, reason, alignmentAvailable = false)

    private fun tokenize(text: String): List<String> {
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        val words = StringBuilder(normalized.length)
        normalized.forEach { char ->
            when {
                Character.getType(char) == Character.NON_SPACING_MARK.toInt() -> Unit
                char.isLetterOrDigit() || char.isWhitespace() -> words.append(char)
                else -> words.append(' ')
            }
        }
        return words.toString().trim().split(WHITESPACE).filter(String::isNotEmpty)
    }

    private fun index(row: Int, column: Int, columns: Int): Int = row * columns + column

    private val WHITESPACE = Regex("\\s+")
}
