package com.yezvi.assistant.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.data.local.memory.MemoryEntryEntity
import com.yezvi.assistant.data.local.memory.MemoryEntryCorrectionEntity
import com.yezvi.assistant.data.local.memory.YezviDatabase
import com.yezvi.assistant.data.local.memory.YezviDatabaseFactory
import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.CaptureStatus
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.domain.memory.MemoryTextVersion
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Every test owns a random, disposable database. No test opens the installed app database. */
@RunWith(AndroidJUnit4::class)
class InformationProposalPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun migratesEmptyV2AndNewInstallationCreatesV3() = runBlocking {
        val migratedName = tempName("v2-empty")
        val freshName = tempName("fresh-v3")
        try {
            createV2Database(migratedName)
            val migrated = YezviDatabaseFactory.create(context, migratedName)
            try {
                assertTrue(migrated.informationProposalDao().findProposals("no-such-extraction").isEmpty())
                assertV3Tables(migratedName)
                assertTrue(migrated.memoryEntryDao().observeAll().first().isEmpty())
                assertNoForeignKeyViolations(migratedName)
            } finally { migrated.close() }
            val reopened = YezviDatabaseFactory.create(context, migratedName)
            try { assertV3Tables(migratedName) } finally { reopened.close() }

            val fresh = YezviDatabaseFactory.create(context, freshName)
            try {
                assertTrue(fresh.informationProposalDao().findProposals("no-such-extraction").isEmpty())
                assertEquals(4, userVersion(freshName))
                assertV3Tables(freshName)
                assertNoForeignKeyViolations(freshName)
            } finally { fresh.close() }
        } finally {
            context.deleteDatabase(migratedName)
            context.deleteDatabase(freshName)
        }
    }

    @Test
    fun migratesPopulatedV2PreservingOriginalCorrectionsAndIndexes() = runBlocking {
        val name = tempName("v2-populated")
        val originalId = "migration-synthetic-entry"
        val raw = "Original privado-sintético intacto 🧪"
        val correction = "Corrección sintética conservada"
        try {
            createV2Database(name)
            seedV2Memory(name, originalId, raw, correction)
            val migrated = YezviDatabaseFactory.create(context, name)
            try {
                assertNotNull(migrated.memoryEntryDao().findById(originalId))
                assertEquals(4, userVersion(name))
                assertEquals(raw, migrated.memoryEntryDao().findById(originalId)?.rawText)
                val correctionRows = migrated.memoryCorrectionDao().findHistory(originalId)
                assertEquals(1, correctionRows.size)
                assertEquals(correction, correctionRows.single().correctedText)
                assertEquals(originalId, correctionRows.single().memoryEntryId)
                assertV3Tables(name)
                assertNoForeignKeyViolations(name)
                assertEquals("ok", integrityCheck(name))
            } finally { migrated.close() }
            val reopened = YezviDatabaseFactory.create(context, name)
            try {
                assertEquals(raw, reopened.memoryEntryDao().findById(originalId)?.rawText)
                assertEquals(correction, reopened.memoryCorrectionDao().findHistory(originalId).single().correctedText)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun failedV2ToV3MigrationRollsBackAndLeavesLegacyRowsReadable() = runBlocking {
        val name = tempName("v2-failure")
        val id = "migration-failure-entry"
        val raw = "Legacy original must survive failed migration"
        try {
            createV2Database(name)
            seedV2Memory(name, id, raw, "Legacy correction must survive")
            rawDb(name).use { it.execSQL("CREATE TABLE information_proposals (migration_blocker TEXT)") }
            var failed = false
            try {
                val database = YezviDatabaseFactory.create(context, name)
                try { database.memoryEntryDao().findById(id) } finally { database.close() }
            } catch (_: Exception) { failed = true }
            assertTrue("DDL collision must abort migration", failed)
            assertEquals(2, userVersion(name))
            rawDb(name).use { sqlite ->
                assertEquals(raw, scalarString(sqlite, "SELECT rawText FROM memory_entries WHERE id = ?", id))
                assertEquals("Legacy correction must survive", scalarString(
                    sqlite, "SELECT correctedText FROM memory_entry_corrections WHERE memoryEntryId = ?", id,
                ))
                assertEquals(0, scalarInt(sqlite,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='information_proposal_versions'"))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun explicitReviewEditingAndRetriesPersistVersionedHistoryWithoutChangingOriginal() = runBlocking {
        val name = tempName("review-edit")
        var database: YezviDatabase? = null
        try {
            database = YezviDatabaseFactory.create(context, name)
            val fixture = seedFixture(database, "01-multiple")
            val repository = RoomInformationProposalRepository(database.informationProposalDao(), true)
            val submitted = repository.submitForReview(fixture.request, fixture.result, 1_800_000_000_000L)
            assertTrue("Fixture should produce an initial batch", submitted is ProposalPersistenceResult.BatchApplied)
            assertTrue("Repeating the same extraction is idempotent",
                repository.submitForReview(fixture.request, fixture.result, 1_800_000_000_000L)
                    is ProposalPersistenceResult.BatchAlreadyApplied)
            val identity = ProposalIdentity(fixture.request.extractionId, "reunion")
            val loaded = repository.find(identity) as ProposalReviewLoadResult.Found
            assertEquals(ProposalReviewState.PROPOSED, loaded.review.state)
            assertTrue(loaded.review.sourcesAreCurrent)
            assertEquals(1, loaded.review.versions.size)

            val editedJson = fixture.json.replace("\"value\": \"reunión con Ana\"", "\"value\": \"Reunión con Ana\"")
            assertNotEquals("The edit fixture must change only capitalization", fixture.json, editedJson)
            val editedDocument = UnvalidatedProposalDocument(
                fixture.request.schemaVersion, fixture.result.document.outcome, editedJson,
            )
            val editAction = ExplicitUserReviewAction.afterExplicitReview("edit-reunion")
            val editResult = repository.appendUserRevision(identity, fixture.request, editedDocument, editAction, 1_800_000_000_100L)
            assertTrue("Validated user edit should append a version", editResult is ProposalPersistenceResult.Applied)
            assertEquals(ProposalPersistenceResult.AlreadyApplied(
                identity, 2, ProposalReviewState.PROPOSED,
            ), repository.appendUserRevision(identity, fixture.request, editedDocument, editAction, 1_800_000_000_200L))

            val currentJson = (repository.find(identity) as ProposalReviewLoadResult.Found).review.document.json
            val confirmAction = ExplicitUserReviewAction.afterExplicitReview("confirm-reunion")
            val confirmed = repository.transition(
                identity, fixture.request,
                UnvalidatedProposalDocument(fixture.request.schemaVersion, fixture.result.document.outcome, currentJson),
                PersistedProposalAction.CONFIRM, confirmAction, 1_800_000_000_300L,
            )
            assertTrue(confirmed is ProposalPersistenceResult.Applied)
            val duplicate = repository.transition(
                identity, fixture.request,
                UnvalidatedProposalDocument(fixture.request.schemaVersion, fixture.result.document.outcome, currentJson),
                PersistedProposalAction.CONFIRM, confirmAction, 1_800_000_000_300L,
            )
            assertTrue(duplicate is ProposalPersistenceResult.AlreadyApplied)
            val after = (repository.find(identity) as ProposalReviewLoadResult.Found).review
            assertEquals(ProposalReviewState.CONFIRMED, after.state)
            assertEquals(listOf(1, 2), after.versions.map { it.version })
            assertEquals(PersistedProposalVersionAuthor.USER, after.versions.last().author)
            assertEquals(ProposalTransitionAction.CONFIRM, after.decisions.last().action)
            assertEquals(fixture.originalText, database.memoryEntryDao().findById(fixture.sourceId)?.rawText)

            database.close(); database = null
            database = YezviDatabaseFactory.create(context, name)
            val reopened = RoomInformationProposalRepository(database.informationProposalDao(), true)
            val persisted = (reopened.find(identity) as ProposalReviewLoadResult.Found).review
            assertEquals(ProposalReviewState.CONFIRMED, persisted.state)
            assertEquals(listOf(1, 2), persisted.versions.map { it.version })
            assertEquals(2, persisted.decisions.size)
            assertEquals(fixture.originalText, database.memoryEntryDao().findById(fixture.sourceId)?.rawText)
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun rejectIsExplicitAndRepeatedActionIsIdempotent() = runBlocking {
        val name = tempName("review-reject")
        var database: YezviDatabase? = null
        try {
            database = YezviDatabaseFactory.create(context, name)
            val fixture = seedFixture(database, "01-multiple")
            val repository = RoomInformationProposalRepository(database.informationProposalDao(), true)
            repository.submitForReview(fixture.request, fixture.result, 1_800_000_000_000L)
            val identity = ProposalIdentity(fixture.request.extractionId, "atlas")
            val action = ExplicitUserReviewAction.afterExplicitReview("reject-atlas")
            val currentJson = (repository.find(identity) as ProposalReviewLoadResult.Found).review.document.json
            val doc = UnvalidatedProposalDocument(fixture.request.schemaVersion, fixture.result.document.outcome, currentJson)
            val rejected = repository.transition(identity, fixture.request, doc, PersistedProposalAction.REJECT, action, 1_800_000_000_001L)
            assertTrue("Explicit reject should apply: $rejected", rejected is ProposalPersistenceResult.Applied)
            val retried = repository.transition(identity, fixture.request, doc, PersistedProposalAction.REJECT, action, 1_800_000_000_001L)
            assertTrue("Same action should be idempotent: $retried", retried is ProposalPersistenceResult.AlreadyApplied)
            assertEquals(ProposalReviewState.REJECTED,
                (repository.find(identity) as ProposalReviewLoadResult.Found).review.state)
        } finally { database?.close(); context.deleteDatabase(name) }
    }

    @Test
    fun syntheticDebugFixtureCannotWriteWithoutTestOnlyOptIn() = runBlocking {
        val name = tempName("fixture-guard")
        var database: YezviDatabase? = null
        try {
            database = YezviDatabaseFactory.create(context, name)
            val fixture = seedFixture(database, "01-multiple")
            val result = fixture.result.copy(mode = ExtractionMode.SYNTHETIC_FIXTURE)
            val repository = RoomInformationProposalRepository(database.informationProposalDao())
            assertEquals(
                ProposalPersistenceRejection.SYNTHETIC_FIXTURE,
                (repository.submitForReview(fixture.request, result, 1_800_000_000_000L)
                    as ProposalPersistenceResult.Rejected).reason,
            )
            assertTrue(database.informationProposalDao().findProposals(fixture.request.extractionId).isEmpty())
        } finally { database?.close(); context.deleteDatabase(name) }
    }

    @Test
    fun correctionRevisionAndUtf16EvidenceSurviveProposalPersistence() = runBlocking {
        val name = tempName("correction-source")
        var database: YezviDatabase? = null
        try {
            database = YezviDatabaseFactory.create(context, name)
            val fixture = seedFixture(database, "12-revision-utf16")
            val repository = RoomInformationProposalRepository(database.informationProposalDao(), true)
            val result = repository.submitForReview(fixture.request, fixture.result, 1_800_000_000_000L)
            assertTrue(result is ProposalPersistenceResult.BatchApplied)
            val identity = ProposalIdentity(fixture.request.extractionId, "envio")
            val stored = database.informationProposalDao().findSources(identity.extractionId, identity.proposalId, 1).single()
            assertEquals("CORRECTION", stored.revisionKind)
            assertEquals("9007199254740993", stored.revisionId)
            assertEquals("[[0,41]]", stored.authorizedRangesJson)
            val review = (repository.find(identity) as ProposalReviewLoadResult.Found).review
            assertTrue(review.document.json.contains("\"startUtf16\":8"))
            assertEquals("Original sintético anterior.", database.memoryEntryDao().findById("s-corrected")?.rawText)
            assertEquals("📝 Ayer envié el informe.\r\nFue el sábado.",
                database.memoryCorrectionDao().findHistory("s-corrected").single().correctedText)
        } finally { database?.close(); context.deleteDatabase(name) }
    }

    @Test
    fun multipleSourcesArePersistedAndDeletingOneRemovesWholeDerivedExtraction() = runBlocking {
        val name = tempName("multi-source-delete")
        var database: YezviDatabase? = null
        try {
            database = YezviDatabaseFactory.create(context, name)
            val fixture = seedFixture(database, "03-patron-observado")
            val repository = RoomInformationProposalRepository(database.informationProposalDao(), true)
            val submitted = repository.submitForReview(fixture.request, fixture.result, 1_800_000_000_000L)
            val ids = when (submitted) {
                is ProposalPersistenceResult.BatchApplied -> submitted.identities
                is ProposalPersistenceResult.BatchAlreadyApplied -> submitted.identities
                else -> fail("Expected an accepted synthetic test batch: $submitted").let { emptyList() }
            }
            assertEquals(1, ids.size)
            val sourceRows = database.informationProposalDao().findSources(fixture.request.extractionId, ids.single().proposalId, 1)
            assertEquals(3, sourceRows.size)
            val secondFixture = seedFixture(database, "01-multiple")
            val secondRepository = RoomInformationProposalRepository(database.informationProposalDao(), true)
            assertTrue(secondRepository.submitForReview(secondFixture.request, secondFixture.result, 1_800_000_000_100L)
                is ProposalPersistenceResult.BatchApplied)
            assertEquals(1, database.memoryEntryDao().deleteById(sourceRows[0].sourceEntryId))
            assertEquals(ProposalReviewLoadResult.Missing, repository.find(ids.single()))
            val deleteCount = database.memoryEntryDao().deleteByIds(listOf(sourceRows[1].sourceEntryId, secondFixture.sourceId))
            assertEquals(2, deleteCount)
            assertTrue(database.informationProposalDao().findProposals(secondFixture.request.extractionId).isEmpty())
            assertNoForeignKeyViolations(name)
            assertEquals("ok", integrityCheck(name))
            database.close(); database = null
            database = YezviDatabaseFactory.create(context, name)
            assertTrue(database.informationProposalDao().findProposals(fixture.request.extractionId).isEmpty())
            assertNoForeignKeyViolations(name)
        } finally { database?.close(); context.deleteDatabase(name) }
    }

    @Test
    fun failedMemoryDeleteRollsBackProposalAndDecisionDeletion() = runBlocking {
        val name = tempName("delete-rollback")
        var database: YezviDatabase? = null
        try {
            database = YezviDatabaseFactory.create(context, name)
            val fixture = seedFixture(database, "01-multiple")
            val repository = RoomInformationProposalRepository(database.informationProposalDao(), true)
            repository.submitForReview(fixture.request, fixture.result, 1_800_000_000_000L)
            val identity = ProposalIdentity(fixture.request.extractionId, "ana")
            val source = fixture.sourceId
            database.close(); database = null
            rawDb(name).use { it.execSQL(
                "CREATE TRIGGER fail_memory_delete BEFORE DELETE ON memory_entries " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END",
            ) }
            database = YezviDatabaseFactory.create(context, name)
            var failed = false
            try { database.memoryEntryDao().deleteById(source) }
            catch (_: SQLiteConstraintException) { failed = true }
            assertTrue("Injected memory failure should be observed", failed)
            assertNotNull(database.memoryEntryDao().findById(source))
            assertNotNull(database.informationProposalDao().findProposal(identity.extractionId, identity.proposalId))
            assertEquals(1, database.informationProposalDao().findVersions(identity.extractionId, identity.proposalId).size)
            assertEquals(1, database.informationProposalDao().findDecisions(identity.extractionId, identity.proposalId).size)
            assertNoForeignKeyViolations(name)
        } finally { database?.close(); context.deleteDatabase(name) }
    }

    @Test
    fun aSourceRevisionChangeBlocksConfirmationWithoutChangingOriginal() = runBlocking {
        val name = tempName("stale-source")
        var database: YezviDatabase? = null
        try {
            database = YezviDatabaseFactory.create(context, name)
            val fixture = seedFixture(database, "01-multiple")
            val repository = RoomInformationProposalRepository(database.informationProposalDao(), true)
            repository.submitForReview(fixture.request, fixture.result, 1_800_000_000_000L)
            database.memoryCorrectionDao().insert(
                com.yezvi.assistant.data.local.memory.MemoryEntryCorrectionEntity(
                    memoryEntryId = fixture.sourceId,
                    correctedText = "Nueva corrección sintética posterior",
                    modifiedAtEpochMillis = 1_800_000_000_100L,
                ),
            )
            val identity = ProposalIdentity(fixture.request.extractionId, "ana")
            val result = repository.transition(
                identity, fixture.request, fixture.result.document, PersistedProposalAction.CONFIRM,
                ExplicitUserReviewAction.afterExplicitReview("confirm-stale-ana"), 1_800_000_000_200L,
            )
            assertEquals(ProposalPersistenceRejection.SOURCE_CHANGED, (result as ProposalPersistenceResult.Rejected).reason)
            assertEquals(ProposalReviewState.PROPOSED,
                (repository.find(identity) as ProposalReviewLoadResult.Found).review.state)
            assertEquals(fixture.originalText, database.memoryEntryDao().findById(fixture.sourceId)?.rawText)
        } finally { database?.close(); context.deleteDatabase(name) }
    }

    @Test
    fun invalidPersistedTransitionHistoryIsNeverPresentedAsConfirmed() = runBlocking {
        val name = tempName("corrupt-history")
        var database: YezviDatabase? = null
        try {
            database = YezviDatabaseFactory.create(context, name)
            val fixture = seedFixture(database, "01-multiple")
            val repository = RoomInformationProposalRepository(database.informationProposalDao(), true)
            repository.submitForReview(fixture.request, fixture.result, 1_800_000_000_000L)
            val identity = ProposalIdentity(fixture.request.extractionId, "ana")
            val submitEvent = database.informationProposalDao()
                .findDecisions(identity.extractionId, identity.proposalId).single()
            database.close(); database = null
            rawDb(name).use { sqlite ->
                sqlite.execSQL(
                    "UPDATE information_proposal_decisions SET toState = 'CONFIRMED' " +
                        "WHERE extractionId = ? AND proposalId = ? AND actionId = ?",
                    arrayOf<Any?>(identity.extractionId, identity.proposalId, submitEvent.actionId),
                )
                sqlite.execSQL(
                    "UPDATE information_proposals SET reviewState = 'CONFIRMED' " +
                        "WHERE extractionId = ? AND proposalId = ?",
                    arrayOf<Any?>(identity.extractionId, identity.proposalId),
                )
            }
            database = YezviDatabaseFactory.create(context, name)
            val checked = RoomInformationProposalRepository(database.informationProposalDao(), true).find(identity)
            assertEquals(
                ProposalPersistenceRejection.STORED_DOCUMENT_INVALID,
                (checked as ProposalReviewLoadResult.Rejected).reason,
            )
        } finally { database?.close(); context.deleteDatabase(name) }
    }

    private suspend fun seedFixture(database: YezviDatabase, name: String): SeededFixture {
        val fixtureJson = context.assets.open("proposal-review/fixtures/$name.json").bufferedReader().use { it.readText() }
        val root = JSONObject(fixtureJson)
        val sourcesRoot = JSONObject(context.assets.open("proposal-review/sources.json").bufferedReader().use { it.readText() })
        val available = sourcesRoot.getJSONArray("sources")
        val sourceSpecs = buildMap {
            for (i in 0 until available.length()) {
                val item = available.getJSONObject(i)
                put(item.getJSONObject("snapshot").getString("sourceEntryId"), item)
            }
        }
        val sourceSnapshots = root.getJSONArray("sourceSnapshots")
        val extractionSources = ArrayList<InformationExtractionSource>()
        var firstSourceId = ""
        var firstOriginal = ""
        for (i in 0 until sourceSnapshots.length()) {
            val snapshot = sourceSnapshots.getJSONObject(i)
            val sourceId = snapshot.getString("sourceEntryId")
            val sourceSpec = sourceSpecs.getValue(sourceId)
            val sourceText = sourceSpec.getString("text")
            val sourceType = CaptureSourceType.valueOf(snapshot.getString("sourceType"))
            val capturedAt = snapshot.getString("capturedAtEpochMillis").toLong()
            val zone = snapshot.getString("timezoneId")
            val revision = snapshot.getJSONObject("revision")
            val correctionId = if (revision.getString("kind") == "CORRECTION") {
                revision.getString("correctionId").toLong()
            } else null
            val originalText = if (correctionId == null) sourceText else "Original sintético anterior."
            database.memoryEntryDao().insert(MemoryEntryEntity(sourceId, originalText, capturedAt, zone, sourceType))
            val memory = MemoryEntry(sourceId, originalText, sourceType, capturedAt, zone, CaptureStatus.SAVED)
            val textVersion = correctionId?.let { id ->
                database.memoryCorrectionDao().insert(MemoryEntryCorrectionEntity(
                    correctionId = id,
                    memoryEntryId = sourceId,
                    correctedText = sourceText,
                    modifiedAtEpochMillis = capturedAt + 1_000L,
                ))
                MemoryTextVersion(sourceId, sourceText, id, capturedAt + 1_000L)
            }
            val jsonRanges = snapshot.getJSONArray("authorizedRanges")
            val ranges = (0 until jsonRanges.length()).map { index ->
                val range = jsonRanges.getJSONObject(index)
                Utf16TextRange(range.getInt("startUtf16"), range.getInt("endUtf16"))
            }
            extractionSources += InformationExtractionSource.from(memory, version = textVersion, ranges = ranges)
            if (i == 0) { firstSourceId = sourceId; firstOriginal = originalText }
        }
        val request = InformationExtractionRequest(
            root.getString("requestId"), root.getString("extractionId"), extractionSources,
        )
        val outcome = ExtractionOutcome.valueOf(root.getString("outcome"))
        val document = UnvalidatedProposalDocument(request.schemaVersion, outcome, fixtureJson)
        return SeededFixture(
            request,
            InformationExtractionResult.Proposals(document, ExtractionMode.MODEL_OUTPUT),
            fixtureJson,
            firstSourceId,
            firstOriginal,
        )
    }

    private fun createV2Database(name: String) {
        rawDb(name).use { sqlite ->
            sqlite.execSQL(
                "CREATE TABLE memory_entries (id TEXT NOT NULL, rawText TEXT NOT NULL, " +
                    "capturedAtEpochMillis INTEGER NOT NULL, timezoneId TEXT NOT NULL, sourceType TEXT NOT NULL, PRIMARY KEY(id))",
            )
            sqlite.execSQL("CREATE INDEX index_memory_entries_capturedAtEpochMillis ON memory_entries(capturedAtEpochMillis)")
            sqlite.execSQL(
                "CREATE TABLE memory_entry_corrections (correctionId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "memoryEntryId TEXT NOT NULL, correctedText TEXT NOT NULL, modifiedAtEpochMillis INTEGER NOT NULL, " +
                    "FOREIGN KEY(memoryEntryId) REFERENCES memory_entries(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            )
            sqlite.execSQL("CREATE INDEX index_memory_entry_corrections_memoryEntryId_correctionId ON memory_entry_corrections(memoryEntryId, correctionId)")
            sqlite.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sqlite.execSQL("INSERT INTO room_master_table(id, identity_hash) VALUES(42, 'edcce8dc0be423acb08cf6e669c11ebe')")
            sqlite.execSQL("PRAGMA user_version = 2")
        }
    }

    private fun seedV2Memory(name: String, id: String, rawText: String, correctedText: String) {
        rawDb(name).use { sqlite ->
            sqlite.execSQL(
                "INSERT INTO memory_entries(id, rawText, capturedAtEpochMillis, timezoneId, sourceType) VALUES(?, ?, ?, ?, ?)",
                arrayOf<Any?>(id, rawText, 1_791_234_567_890L, "America/Lima", "VOICE"),
            )
            sqlite.execSQL(
                "INSERT INTO memory_entry_corrections(memoryEntryId, correctedText, modifiedAtEpochMillis) VALUES(?, ?, ?)",
                arrayOf<Any?>(id, correctedText, 1_791_234_568_000L),
            )
        }
    }

    private fun assertV3Tables(name: String) = rawDb(name).use { sqlite ->
        listOf(
            "information_proposals", "information_proposal_versions",
            "information_proposal_sources", "information_proposal_decisions",
        ).forEach { table -> assertEquals(1, scalarInt(sqlite,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name = ?", table)) }
        val requiredIndices = listOf(
            "index_information_proposals_reviewState_updatedAtEpochMillis",
            "index_information_proposal_versions_extractionId_proposalId_actionId",
            "index_information_proposal_sources_extractionId_proposalId_version",
            "index_information_proposal_sources_sourceEntryId",
            "index_information_proposal_decisions_extractionId_proposalId_version",
            "index_information_proposal_decisions_extractionId_proposalId_occurredAtEpochMillis",
            "index_information_proposal_decisions_extractionId_proposalId_sequence",
        )
        requiredIndices.forEach { index -> assertEquals(1, scalarInt(sqlite,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name = ?", index)) }
        val sourceForeignKeys = linkedMapOf<String, String>()
        sqlite.rawQuery("PRAGMA foreign_key_list(information_proposal_sources)", null).use { cursor ->
            val tableIndex = cursor.getColumnIndexOrThrow("table")
            val onDeleteIndex = cursor.getColumnIndexOrThrow("on_delete")
            while (cursor.moveToNext()) sourceForeignKeys[cursor.getString(tableIndex)] = cursor.getString(onDeleteIndex)
        }
        assertEquals("CASCADE", sourceForeignKeys["information_proposal_versions"])
        assertEquals("RESTRICT", sourceForeignKeys["memory_entries"])
        val correctionForeignKeys = linkedMapOf<String, String>()
        sqlite.rawQuery("PRAGMA foreign_key_list(memory_entry_corrections)", null).use { cursor ->
            while (cursor.moveToNext()) correctionForeignKeys[cursor.getString(cursor.getColumnIndexOrThrow("table"))] =
                cursor.getString(cursor.getColumnIndexOrThrow("on_delete"))
        }
        assertEquals("CASCADE", correctionForeignKeys["memory_entries"])
    }

    private fun assertNoForeignKeyViolations(name: String) = rawDb(name).use { sqlite ->
        sqlite.rawQuery("PRAGMA foreign_key_check", null).use { assertEquals("No FK violations", 0, it.count) }
    }

    private fun integrityCheck(name: String): String = rawDb(name).use { sqlite ->
        sqlite.rawQuery("PRAGMA integrity_check", null).use { assertTrue(it.moveToFirst()); it.getString(0) }
    }

    private fun userVersion(name: String): Int = rawDb(name).use { scalarInt(it, "PRAGMA user_version") }

    private fun rawDb(name: String): SQLiteDatabase {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(file, null)
    }

    private fun scalarInt(sqlite: SQLiteDatabase, sql: String, vararg args: String): Int =
        sqlite.rawQuery(sql, args).use { assertTrue(it.moveToFirst()); it.getInt(0) }

    private fun scalarString(sqlite: SQLiteDatabase, sql: String, vararg args: String): String =
        sqlite.rawQuery(sql, args).use { assertTrue(it.moveToFirst()); it.getString(0) }

    private fun tempName(suffix: String) = "f408-${System.nanoTime()}-$suffix.db"

    private data class SeededFixture(
        val request: InformationExtractionRequest,
        val result: InformationExtractionResult.Proposals,
        val json: String,
        val sourceId: String,
        val originalText: String,
    )
}

