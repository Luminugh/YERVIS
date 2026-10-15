package com.yezvi.assistant.data.speech

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSpeechVocabularyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsSyntheticNamesAndTechnicalTermsWithExactCase() {
        val vocabulary = fixture().parse()
        val terms = listOf("YEZVI", "Kotlin", "Jetpack Compose", "Nalvero", "Zorvian", "Névara")

        val result = vocabulary.validateTerms(terms)

        assertEquals(terms, result.acceptedTerms)
        assertEquals("YEZVI/Kotlin/Jetpack Compose/Nalvero/Zorvian/Névara", result.hotwords)
        assertTrue(result.rejectedTerms.isEmpty())
    }

    @Test
    fun normalizesOnlyAsciiSpacesAndDeduplicatesWithoutCaseFolding() {
        val result = fixture().parse().validateTerms(
            listOf("  Jetpack   Compose  ", "Jetpack Compose", "YEZVI", "yezvi"),
        )

        assertEquals(listOf("Jetpack Compose", "YEZVI", "yezvi"), result.acceptedTerms)
        assertTrue(result.rejectedTerms.isEmpty())
    }

    @Test
    fun preservesPrecomposedAccentsAndRejectsMissingCombiningCharacters() {
        val result = fixture().parse().validateTerms(listOf("Névara", "Ne\u0301vara"))

        assertEquals(listOf("Névara"), result.acceptedTerms)
        assertEquals(
            listOf(rejected("Ne\u0301vara", LocalSpeechVocabulary.RejectionReason.UNREPRESENTABLE_CHARACTER)),
            result.rejectedTerms,
        )
    }

    @Test
    fun reportsEachUnsupportedTermAndNeverIncludesItInHotwords() {
        val terms = listOf("中文", "Zorvian😀", "Nøvara", "123")
        val result = fixture().parse().validateTerms(terms)

        assertTrue(result.acceptedTerms.isEmpty())
        assertEquals("", result.hotwords)
        assertEquals(terms.map {
            rejected(it, LocalSpeechVocabulary.RejectionReason.UNREPRESENTABLE_CHARACTER)
        }, result.rejectedTerms)
    }

    @Test
    fun rejectsPhraseScoreAndControlInjectionAndAmbiguousWhitespace() {
        val delimiters = listOf("Kotlin/YEZVI", "Kotlin :3.0", "Kotlin:3")
        val unsafe = listOf("Kotlin\nYEZVI", "Kotlin\rYEZVI", "Kotlin\tYEZVI", "Kotlin\u0000",
            "Jetpack\u00A0Compose", "Jetpack\u2003Compose", "YEZ\u200BVI", "▁YEZVI", "YEZVI\uD800")
        val result = fixture().parse().validateTerms(delimiters + unsafe)

        assertTrue(result.acceptedTerms.isEmpty())
        assertEquals(delimiters.map {
            rejected(it, LocalSpeechVocabulary.RejectionReason.RESERVED_DELIMITER)
        } + unsafe.map {
            rejected(it, LocalSpeechVocabulary.RejectionReason.UNSAFE_CHARACTER)
        }, result.rejectedTerms)
    }

    @Test
    fun rejectsEmptyTermsButAcceptsEmptyListAsDisabledBias() {
        val vocabulary = fixture().parse()

        assertEquals(listOf(rejected("", LocalSpeechVocabulary.RejectionReason.EMPTY),
            rejected("   ", LocalSpeechVocabulary.RejectionReason.EMPTY)),
            vocabulary.validateTerms(listOf("", "   ")).rejectedTerms)
        assertEquals("", vocabulary.validateTerms(emptyList()).hotwords)
    }

    @Test
    fun doesNotGuessRepresentationFromLettersInsideLongerPieces() {
        val vocabulary = Fixture(listOf("<unk>", "▁", "ye", "<blk>")).parse()

        assertEquals(listOf(rejected("ye", LocalSpeechVocabulary.RejectionReason.UNREPRESENTABLE_CHARACTER)),
            vocabulary.validateTerms(listOf("ye")).rejectedTerms)
    }

    @Test
    fun validatesConsecutiveIdsMatchingOrderedBpeAndExactDerivedScores() {
        val fixture = Fixture(listOf("<unk>", "▁", "a", "é", "<blk>"))

        assertEquals(5, fixture.parse().tokenCount)
        assertInvalid(fixture.tokens.replace("a 2", "a 3"), fixture.bpe, fixture.size)
        assertInvalid(fixture.tokens.replace("a 2", "a 02"), fixture.bpe, fixture.size)
        assertInvalid(fixture.tokens.replace("a 2", "a 2 extra"), fixture.bpe, fixture.size)
        assertInvalid(fixture.tokens, fixture.bpe.replace("a\t-1.0", "b\t-1.0"), fixture.size)
        assertInvalid(fixture.tokens, fixture.bpe.replace("a\t-1.0", "a\t-0.5"), fixture.size)
        assertInvalid(fixture.tokens, fixture.bpe.replace("a\t-1.0", "a\tNaN"), fixture.size)
        assertInvalid(fixture.tokens, fixture.bpe.replace("a\t-1.0", "a -1.0"), fixture.size)
    }

    @Test
    fun rejectsDuplicateOrMissingRequiredSymbolsAndWrongRowCount() {
        for (pieces in listOf(
            listOf("<unk>", "▁", "a", "a", "<blk>"),
            listOf("x", "▁", "a", "<blk>"),
            listOf("<unk>", "a", "<blk>"),
            listOf("<unk>", "▁", "a", "x"),
        )) {
            val fixture = Fixture(pieces)
            assertInvalid(fixture.tokens, fixture.bpe, fixture.size)
        }
        val fixture = fixture()
        assertInvalid(fixture.tokens, fixture.bpe, fixture.size + 1)
        expectIOException { LocalSpeechVocabulary.fromTexts(fixture.tokens, fixture.bpe) }
    }

    @Test
    fun rejectsBomCrLfMissingFinalLfAndBlankRows() {
        val fixture = fixture()
        for (invalid in listOf("\uFEFF" + fixture.tokens, fixture.tokens.replace("\n", "\r\n"),
            fixture.tokens.dropLast(1), fixture.tokens + "\n")) {
            assertInvalid(invalid, fixture.bpe, fixture.size)
        }
        for (invalid in listOf("\uFEFF" + fixture.bpe, fixture.bpe.replace("\n", "\r\n"),
            fixture.bpe.dropLast(1), fixture.bpe + "\n")) {
            assertInvalid(fixture.tokens, invalid, fixture.size)
        }
    }

    @Test
    fun readsUtf8FilesAndRejectsMalformedBytesBeforeNativeUse() {
        val fixture = fixture()
        val tokens = temporaryFolder.newFile("tokens.txt").apply { writeText(fixture.tokens, Charsets.UTF_8) }
        val bpe = temporaryFolder.newFile("bpe.vocab").apply { writeText(fixture.bpe, Charsets.UTF_8) }

        val vocabulary = LocalSpeechVocabulary.fromFiles(tokens, bpe, fixture.size)
        assertEquals(listOf("Névara"), vocabulary.validateTerms(listOf("Névara")).acceptedTerms)
        tokens.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        expectIOException { LocalSpeechVocabulary.fromFiles(tokens, bpe, fixture.size) }
    }

    private fun fixture() = Fixture(listOf("<unk>", "▁") +
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZáéíóúñüÁÉÍÓÚÑÜ".map(Char::toString) + "<blk>")

    private fun rejected(term: String, reason: LocalSpeechVocabulary.RejectionReason) =
        LocalSpeechVocabulary.RejectedTerm(term, reason)

    private fun assertInvalid(tokens: String, bpe: String, size: Int) = expectIOException {
        LocalSpeechVocabulary.fromTexts(tokens, bpe, size)
    }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            fail("Expected vocabulary validation failure")
        } catch (_: IOException) {
            // Resource details and term contents must not be written to logs.
        }
    }

    private class Fixture(pieces: List<String>) {
        val size = pieces.size
        val tokens = pieces.mapIndexed { index, piece -> "$piece $index\n" }.joinToString("")
        val bpe = pieces.joinToString("") { "$it\t-1.0\n" }
        fun parse() = LocalSpeechVocabulary.fromTexts(tokens, bpe, size)
    }
}
