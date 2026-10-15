package com.yezvi.assistant.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class WordErrorScorerTest {
    @Test
    fun ignoresCasePunctuationAndDiacriticsButKeepsWordOrder() {
        val same = WordErrorScorer.score("Árbol, CPU!", "arbol cpu")
        assertEquals(0.0, same.werPercent!!, 0.0)
        assertEquals(2, same.correctWords)

        val reordered = WordErrorScorer.score("alpha beta", "beta alpha")
        assertEquals(2, reordered.substitutions)
        assertEquals(100.0, reordered.werPercent!!, 0.0)
    }

    @Test
    fun reportsSubstitutionsDeletionsInsertionsAndStandardWer() {
        val stats = WordErrorScorer.score("uno dos tres cuatro", "uno otro tres nuevo")
        assertEquals(2, stats.substitutions)
        assertEquals(0, stats.deletions)
        assertEquals(0, stats.insertions)
        assertEquals(2, stats.correctWords)
        assertEquals(50.0, stats.werPercent!!, 0.0)

        val withDeletion = WordErrorScorer.score("uno dos tres", "uno tres")
        assertEquals(1, withDeletion.deletions)
        assertEquals(100.0 / 3.0, withDeletion.werPercent!!, 0.0001)

        val withInsertion = WordErrorScorer.score("uno", "uno dos")
        assertEquals(1, withInsertion.insertions)
        assertEquals(100.0, withInsertion.werPercent!!, 0.0)
    }

    @Test
    fun doesNotRewriteDigitsOrTechnicalTerms() {
        val stats = WordErrorScorer.score("8792 CPU Jetpack Compose", "ocho siete nueve dos cepeu Jetpack Compose")
        assertEquals(4, stats.referenceWords)
        assertEquals(7, stats.hypothesisWords)
        assertNotNull(stats.werPercent)
        assertEquals(2, stats.correctWords)
    }

    @Test
    fun emptyReferenceHasInsertionsButNoDefinedWer() {
        val stats = WordErrorScorer.score("...", "una palabra")
        assertEquals(0, stats.referenceWords)
        assertEquals(2, stats.insertions)
        assertNull(stats.werPercent)
        assertNotNull(stats.unavailableReason)
    }

    @Test
    fun refusesOversizedAlignmentInsteadOfAllocatingUnboundedMatrix() {
        val reference = List(1_000) { "palabra" }.joinToString(" ")
        val hypothesis = List(1_000) { "otra" }.joinToString(" ")
        val stats = WordErrorScorer.score(reference, hypothesis)
        assertNull(stats.werPercent)
        assertNotNull(stats.unavailableReason)
    }
}
