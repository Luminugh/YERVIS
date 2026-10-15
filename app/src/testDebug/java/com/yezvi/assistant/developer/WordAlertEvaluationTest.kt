package com.yezvi.assistant.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WordAlertEvaluationTest {
    @Test
    fun evaluatesSubstitutionsInsertionsFalseAlertsAndMissingWordsSeparately() {
        val analysis = analysis("uno otro extra tres", setOf(0, 1, 2))
        val stats = WordAlertEvaluation.evaluate("uno dos tres", analysis)
        assertNull(stats.unavailableReason)
        assertEquals(3, stats.alerts)
        assertEquals(2, stats.trueAlerts)
        assertEquals(1, stats.falseAlerts)
        assertEquals(2, stats.erroneousEmittedWords)
        assertEquals(0, stats.omissions)
        assertEquals(2.0 / 3, stats.precision!!, 0.000001)
        assertEquals(1.0, stats.detectedErrorFraction!!, 0.0)
        assertEquals(0.75, stats.markedWordFraction!!, 0.0)

        val omitted = WordAlertEvaluation.evaluate("uno dos tres", analysis("uno tres", setOf(1)))
        assertEquals(1, omitted.omissions)
        assertEquals(0, omitted.erroneousEmittedWords)
        assertEquals(1, omitted.falseAlerts)
        assertNull(omitted.detectedErrorFraction)
    }

    @Test
    fun referenceDoesNotChangeWhichWordsWereFlaggedAndNormalizationMatchesWer() {
        val result = analysis("Árbol, CPU!", setOf(1))
        assertEquals(0, WordAlertEvaluation.evaluate("arbol cpu", result).trueAlerts)
        assertEquals(1, WordAlertEvaluation.evaluate("arbol gpu", result).trueAlerts)
        assertEquals(setOf(1), result.reviewWordIndices)
    }

    @Test
    fun zeroAlertsAndEmptyReferenceDoNotInventPrecision() {
        assertNull(WordAlertEvaluation.evaluate("uno", analysis("otro", emptySet())).precision)
        assertNotNull(WordAlertEvaluation.evaluate("...", analysis("uno", emptySet())).unavailableReason)
        assertNotNull(WordAlertEvaluation.evaluate("uno", analysis("uno", setOf(2))).unavailableReason)
        assertNotNull(WordAlertEvaluation.evaluate("uno", analysis("uno", emptySet()).copy(hasValidatedThreshold = false)).unavailableReason)
    }

    @Test
    fun keepsMatrixBoundedForLongExactReference() {
        val text = List(512) { "uno" }.joinToString(" ")
        val stats = WordAlertEvaluation.evaluate(text, analysis(text, emptySet()))
        assertNotNull(stats.unavailableReason)
    }

    private fun analysis(text: String, alerts: Set<Int>): WordUncertaintyAnalysis {
        var offset = 0
        val words = text.split(' ').map { word ->
            WordUncertaintyWord(word, offset, offset + word.length, -0.1f, 0f).also { offset += word.length + 1 }
        }
        return WordUncertaintyAnalysis(text, words, words.size, reviewWordIndices = alerts, hasValidatedThreshold = true)
    }
}
