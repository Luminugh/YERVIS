package com.yezvi.assistant.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordUncertaintyAnalysisTest {
    @Test
    fun aggregatesMinimumBpeScoreAndExcludesStandalonePunctuation() {
        val result = analyze("Jetpack Compose.", listOf("▁Jet", "pack", "▁Com", "pose", "."),
            floatArrayOf(-0.1f, -2f, -0.8f, -0.2f, -9f))
        assertNull(result.unavailableReason)
        assertEquals(listOf("Jetpack", "Compose"), result.words.map { it.text })
        assertEquals(listOf(-2f, -0.8f), result.words.map { it.minimumTokenLogProbability })
        assertEquals(listOf(0, 8), result.words.map { it.startUtf16 })
        assertEquals(listOf(7, 15), result.words.map { it.endUtf16 })
        assertTrue(result.reviewWordIndices.isEmpty())
    }

    @Test
    fun boundaryAndPunctuationPiecesNeverCreateWordsOrChangeText() {
        val result = analyze("¡Hola! , mundo", listOf("▁", "¡", "Hola", "!", "▁,", "▁mundo"),
            floatArrayOf(-10f, -8f, -0.2f, -9f, -7f, -0.5f))
        assertNull(result.unavailableReason)
        assertEquals("¡Hola! , mundo", result.text)
        assertEquals(listOf("Hola", "mundo"), result.words.map { it.text })
        assertEquals(listOf(-0.2f, -0.5f), result.words.map { it.minimumTokenLogProbability })
        val adjacent = analyze("Hola,mundo", listOf("▁Hola", ",", "mundo"), floatArrayOf(-0.2f, -9f, -0.3f))
        assertEquals(listOf("Hola", "mundo"), adjacent.words.map { it.text })
        assertEquals(listOf(0, 5), adjacent.words.map { it.startUtf16 })
    }

    @Test
    fun acceptsNativeJniAsciiSpacePiecesWithoutScoringBoundariesOrPunctuation() {
        val result = analyze("Esta es prueba.", listOf(" Esta", " es", " ", "prueba", "."),
            floatArrayOf(-0.1f, -0.2f, -9f, -0.3f, -8f))
        assertNull(result.unavailableReason)
        assertEquals(listOf("Esta", "es", "prueba"), result.words.map { it.text })
        assertEquals(listOf(-0.1f, -0.2f, -0.3f), result.words.map { it.minimumTokenLogProbability })
        assertEquals(listOf(0, 5, 8), result.words.map { it.startUtf16 })
        assertEquals("Esta es prueba.", result.text)
        listOf('\t', '\n', '\u00a0').forEach { whitespace ->
            assertNotNull(analyze("ho${whitespace}la", listOf("ho${whitespace}la"),
                floatArrayOf(-0.1f)).unavailableReason)
        }
    }

    @Test
    fun keepsExactUnicodeAndUtf16OffsetsAcrossSegments() {
        val session = WordUncertaintySession("test")
        val first = session.append(7, "", "  árbol ", listOf("▁ár", "bol"), listOf(0f, 0.1f),
            floatArrayOf(-0.1f, -0.2f), 1_000.0)
        assertEquals("árbol", first.text)
        val second = session.append(7, "árbol", "𐐀rbol", listOf("▁𐐀", "rbol"), listOf(0f, 0.1f),
            floatArrayOf(-0.3f, -0.4f), 1_000.0)
        assertNull(second.unavailableReason)
        assertEquals("árbol 𐐀rbol", second.text)
        assertEquals(6, second.words.last().startUtf16)
        assertEquals(12, second.words.last().endUtf16)
        assertEquals("𐐀rbol", second.text.substring(second.words.last().startUtf16, second.words.last().endUtf16))
    }

    @Test
    fun rejectsMissingInvalidUnalignedAndNonReconstructingMetadata() {
        val cases = listOf(
            analyze("hola", listOf("▁hola"), null),
            analyze("hola", listOf("▁hola"), floatArrayOf(-1f, -2f)),
            analyze("hola", listOf("▁hola"), floatArrayOf(Float.NaN)),
            analyze("hola", listOf("▁hola"), floatArrayOf(Float.NEGATIVE_INFINITY)),
            analyze("hola", listOf("▁hola"), floatArrayOf(0.1f)),
            analyze("hola", listOf("▁otro"), floatArrayOf(-1f)),
            analyze("hola", listOf("<unk>"), floatArrayOf(-1f)),
            analyze("hola", listOf("▁hola"), floatArrayOf(-1f), listOf(-1f)),
            analyze("hola", listOf("▁hola"), floatArrayOf(-1f), emptyList()),
            analyze("hola", listOf("▁ho", "la"), floatArrayOf(-1f, -1f), listOf(0.5f, 0.2f)),
            analyze("hola", listOf("▁hola"), floatArrayOf(-1f), listOf(2f)),
        )
        cases.forEach {
            assertNotNull(it.unavailableReason)
            assertTrue(it.words.isEmpty())
            assertEquals("hola", it.text)
        }
    }

    @Test
    fun rejectsStandaloneUnicodeMarksEvenWhenOnePieceAlsoContainsARealWord() {
        val result = analyze("a \u0301", listOf("▁a▁\u0301"), floatArrayOf(-0.2f))
        assertNotNull(result.unavailableReason)
        assertTrue(result.words.isEmpty())
        assertEquals("a \u0301", result.text)
    }

    @Test
    fun refusesNewSessionIdsAndUnexpectedPrefixPermanentlyUntilReset() {
        val old = WordUncertaintySession("test")
        append(old, 1, "", "uno")
        assertNotNull(append(old, 2, "uno", "dos").unavailableReason)
        assertNotNull(append(old, 1, "uno", "tres").unavailableReason)
        val fresh = WordUncertaintySession("test")
        assertNull(append(fresh, 2, "", "dos").unavailableReason)
        assertEquals(listOf("dos"), append(WordUncertaintySession("test"), 3, "", "dos").words.map { it.text })

        val reordered = WordUncertaintySession("test")
        append(reordered, 1, "", "uno")
        assertNotNull(append(reordered, 1, "otro", "dos").unavailableReason)
    }

    @Test
    fun tokenAndWordSessionLimitsDiscardAllAnalysisWithoutGrowingArrays() {
        val tokens = List(WordUncertaintySession.MAX_TOKENS) { "▁" }
        val tokenSession = WordUncertaintySession("test")
        assertNull(tokenSession.append(1, "", "", tokens, List(tokens.size) { 0f },
            FloatArray(tokens.size) { -0.1f }, 1_000.0).unavailableReason)
        val overflow = tokenSession.append(1, "", "a", listOf("▁a"), listOf(0f), floatArrayOf(-1f), 1_000.0)
        assertNotNull(overflow.unavailableReason)
        assertTrue(overflow.words.isEmpty())
        assertEquals(WordUncertaintySession.MAX_TOKENS, overflow.tokenCount)

        val words = List(WordUncertaintySession.MAX_WORDS) { "a" }.joinToString(" ")
        val wordSession = WordUncertaintySession("test")
        val initial = wordSession.append(1, "", words, List(WordUncertaintySession.MAX_WORDS) { "▁a" },
            List(WordUncertaintySession.MAX_WORDS) { 0f }, FloatArray(WordUncertaintySession.MAX_WORDS) { -1f }, 1_000.0)
        assertEquals(512, initial.words.size)
        val tooMany = append(wordSession, 1, words, "a")
        assertNotNull(tooMany.unavailableReason)
        assertTrue(tooMany.words.isEmpty())
    }

    @Test
    fun activatesThresholdOnlyWithExactProfileAndIndependentQualityGate() {
        val threshold = WordReviewThreshold("runtime/model/decoder-v1", -1f, 0.9, 0.1, 0.85, 30, 50)
        assertTrue(threshold.matches("runtime/model/decoder-v1"))
        assertFalse(threshold.matches("different-runtime/model/decoder-v1"))
        assertFalse(threshold.copy(evaluationAlerts = 29).matches(threshold.profileId))
        assertFalse(threshold.copy(evaluationDetectableErrors = 49).matches(threshold.profileId))
        assertFalse(threshold.copy(evaluationPrecision = 0.79).matches(threshold.profileId))
        assertFalse(threshold.copy(adjustmentMarkedFraction = 0.16).matches(threshold.profileId))
        assertFalse(threshold.copy(maximumLogProbability = Float.NaN).matches(threshold.profileId))
        val session = WordUncertaintySession(threshold.profileId, threshold)
        val result = session.append(1, "", "uno dos", listOf("▁uno", "▁dos"), listOf(0f, 0.1f),
            floatArrayOf(-0.1f, -2f), 1_000.0)
        assertTrue(result.hasValidatedThreshold)
        assertEquals(setOf(1), result.reviewWordIndices)
        val unavailable = append(WordUncertaintySession("other-profile", threshold), 1, "", "uno")
        assertFalse(unavailable.hasValidatedThreshold)
        assertTrue(unavailable.reviewWordIndices.isEmpty())
    }

    private fun analyze(text: String, tokens: List<String>, scores: FloatArray?,
        times: List<Float> = List(tokens.size) { it / 10f }) =
        WordUncertaintySession.analyzeSegment(text, tokens, times, scores, 1_000.0)

    private fun append(session: WordUncertaintySession, id: Long, before: String, text: String) =
        session.append(id, before, text, listOf("▁$text"), listOf(0f), floatArrayOf(-0.2f), 1_000.0)
}
