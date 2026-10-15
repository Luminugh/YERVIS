package com.yezvi.assistant.intelligence

import com.yezvi.assistant.domain.intelligence.ProposalReviewState
import com.yezvi.assistant.domain.intelligence.TemporalResolutionStatus
import com.yezvi.assistant.presentation.intelligence.ProposalReviewDemoLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalReviewDemoLoaderTest {
    private val fixtureRoot = F402Fixtures.root

    @Test
    fun onlyCanonicalPositiveFixturesPassingValidationReachReview() = runBlocking {
        val session = loader().load()
        assertTrue(session.proposals.isNotEmpty())
        assertEquals(1, session.noProposalScenarios)
        assertTrue(session.proposals.all { it.lifecycle.state == ProposalReviewState.PROPOSED })
        assertTrue(session.proposals.all { it.card.reviewState == ProposalReviewState.PROPOSED })
        assertEquals(
            setOf("TASK", "EVENT", "ROUTINE", "HABIT", "REMINDER", "IDEA", "FACT", "DECISION", "GOAL", "PERSON", "PROJECT"),
            session.proposals.map { it.card.category }.toSet(),
        )
        assertEquals(listOf("EVENT", "TASK", "FACT", "ROUTINE", "HABIT"),
            session.proposals.map { it.card.category }.distinct().take(5))
    }

    @Test
    fun everyDisplayedCitationMatchesItsExactUtf16SourceRange() = runBlocking {
        val session = loader().load()
        var evidenceCount = 0
        session.proposals.forEach { item ->
            assertTrue(item.card.fields.isNotEmpty())
            item.card.fields.forEach { field ->
                field.evidence.forEach { evidence ->
                    val source = item.card.sources.single { it.sourceEntryId == evidence.sourceEntryId }
                    assertTrue(evidence.startUtf16 >= 0)
                    assertTrue(evidence.endUtf16 <= source.reviewedText.length)
                    assertEquals(
                        evidence.quote,
                        source.reviewedText.substring(evidence.startUtf16, evidence.endUtf16),
                    )
                    evidenceCount++
                }
            }
        }
        assertTrue("Expected field-level evidence for the canonical sample set", evidenceCount > 40)
    }

    @Test
    fun ambiguousEventKeepsItsExpressionWithoutInventingDateOrTime() = runBlocking {
        val event = loader().load().proposals.single { item ->
            item.card.category == "EVENT" && item.card.sources.any { it.sourceEntryId == "s-ambiguous" }
        }
        val temporal = event.card.fields.single { it.key == "startsAt" }.temporal
        assertNotNull(temporal)
        assertEquals(TemporalResolutionStatus.AMBIGUOUS, temporal!!.status)
        assertEquals("el viernes por la tarde", temporal.expression)
        assertEquals(null, temporal.localDate)
        assertEquals(null, temporal.localTime)
    }

    @Test
    fun observedRoutineIsStillOnlyAProposalAndReminderIsNotActivated() = runBlocking {
        val items = loader().load().proposals
        val observed = items.single { item ->
            item.card.category == "ROUTINE" && item.card.sources.any { it.sourceEntryId.startsWith("s-observed-") }
        }
        assertEquals("OBSERVED", observed.card.fields.single { it.key == "patternKind" }.value)
        assertEquals(ProposalReviewState.PROPOSED, observed.lifecycle.state)

        val reminder = items.single { it.card.category == "REMINDER" }
        assertEquals(ProposalReviewState.PROPOSED, reminder.lifecycle.state)
        assertFalse(reminder.card.fields.any { it.key == "alarmEnabled" })
    }

    @Test
    fun correctingTheUiProjectionDoesNotChangeAnyOriginalOrCitedFixture() = runBlocking {
        val item = loader().load().proposals.first { it.card.category == "TASK" }
        val field = item.card.fields.first { it.key == "title" }
        val original = item.card.sources.first().reviewedText
        val edited = item.card.copy(fields = item.card.fields.map {
            if (it.pointer == field.pointer) it.copy(localEdit = "preparar el resumen semanal") else it
        })

        assertEquals(field.value, item.card.fields.first { it.pointer == field.pointer }.value)
        assertEquals("preparar el resumen semanal", edited.fields.first { it.pointer == field.pointer }.localEdit)
        assertEquals(original, item.card.sources.first().reviewedText)
        assertEquals(field.evidence, edited.fields.first { it.pointer == field.pointer }.evidence)
    }

    private fun loader(): ProposalReviewDemoLoader = ProposalReviewDemoLoader { assetPath ->
        val relative = assetPath.removePrefix("proposal-review/")
        val file = if (relative == "sources.json") File(fixtureRoot, "fixtures/sources.json")
        else File(fixtureRoot, "fixtures/valid/${relative.substringAfter("fixtures/")}")
        require(file.isFile) { "Missing canonical fixture ${file.name}" }
        file.readText(Charsets.UTF_8)
    }
}
