package com.yezvi.assistant.data.repository

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yezvi.assistant.data.intelligence.deduplication.*
import com.yezvi.assistant.data.local.memory.*
import com.yezvi.assistant.domain.intelligence.*
import com.yezvi.assistant.domain.memory.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier
import java.util.UUID

/** Fails BEFORE opening a database unless the APK has a separate synthetic-only Android UID. */
@RunWith(AndroidJUnit4::class)
class ProposalDeduplicationPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also {
        check(it.packageName == "com.yezvi.assistant.persistenceisolated")
    }
    private val legacyTables = listOf("memory_entries", "memory_entry_corrections", "information_proposals",
        "information_proposal_versions", "information_proposal_sources", "information_proposal_decisions")
    private fun name() = "f409-${UUID.randomUUID()}.db"
    private fun fixture(name: String): JSONObject = JSONObject(context.assets.open("proposal-review/fixtures/$name.json")
        .bufferedReader().use { it.readText() })
    private fun db(name: String) = YezviDatabaseFactory.create(context, name)
    private inline fun <T> YezviDatabase.use(block: (YezviDatabase) -> T): T =
        try { block(this) } finally { close() }
    private fun raw(name: String): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).also {
        it.parentFile?.mkdirs()
    }, null)
    private fun scalar(name: String, query: String): String = raw(name).use { sql ->
        sql.rawQuery(query, null).use { assertTrue(it.moveToFirst()); it.getString(0) }
    }
    private fun assertIntegrity(name: String) {
        assertEquals("ok", scalar(name, "PRAGMA integrity_check"))
        raw(name).use { it.rawQuery("PRAGMA foreign_key_check", null).use { rows -> assertEquals(0, rows.count) } }
    }
    private fun fingerprint(name: String) = raw(name).use { sql ->
        proposalDigest(legacyTables.joinToString("|") { table ->
            sql.rawQuery("SELECT * FROM `$table` ORDER BY rowid", null).use { rows ->
                buildString { while (rows.moveToNext()) {
                    append(JSONArray((0 until rows.columnCount).map { rows.getString(it) }).toString()); append('\n')
                } }
            }
        })
    }
    private fun createLegacy(name: String, version: Int) {
        val schema = JSONObject(instrumentation.context.assets
            .open("com.yezvi.assistant.data.local.memory.YezviDatabase/$version.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        raw(name).use { sql ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL("PRAGMA user_version=$version")
        }
    }
    private fun insert(sql: SQLiteDatabase, table: String, row: Any) {
        val values = ContentValues()
        row.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.forEach { field ->
            field.isAccessible = true
            when (val value = field.get(row)) {
                null -> values.putNull(field.name)
                is String -> values.put(field.name, value)
                is Long -> values.put(field.name, value)
                is Int -> values.put(field.name, value)
                is Enum<*> -> values.put(field.name, value.name)
                else -> error("Unexpected synthetic fixture field")
            }
        }
        sql.insertOrThrow(table, null, values)
    }
    private suspend fun seed(database: YezviDatabase?, name: String, root: JSONObject): InformationExtractionRequest {
        val specs = JSONObject(context.assets.open("proposal-review/sources.json").bufferedReader().use { it.readText() })
            .getJSONArray("sources")
        val available = (0 until specs.length()).associate { index ->
            val spec = specs.getJSONObject(index)
            spec.getJSONObject("snapshot").getString("sourceEntryId") to spec.getString("text")
        }
        val snapshots = root.getJSONArray("sourceSnapshots")
        val sources = (0 until snapshots.length()).map { index ->
            val source = snapshots.getJSONObject(index)
            val id = source.getString("sourceEntryId")
            val text = available.getValue(id)
            val captured = source.getString("capturedAtEpochMillis").toLong()
            val zone = source.getString("timezoneId")
            val type = CaptureSourceType.valueOf(source.getString("sourceType"))
            val revision = source.getJSONObject("revision")
            val correction = if (revision.getString("kind") == "CORRECTION") revision.getString("correctionId").toLong() else null
            val original = if (correction == null) text else "Original sintético anterior."
            val entity = MemoryEntryEntity(id, original, captured, zone, type)
            if (database != null) {
                if (database.memoryEntryDao().findById(id) == null) database.memoryEntryDao().insert(entity)
            } else raw(name).use { sql -> insert(sql, "memory_entries", entity) }
            val textVersion = correction?.let {
                val corrected = MemoryEntryCorrectionEntity(it, id, text, captured + 1000)
                if (database != null) database.memoryCorrectionDao().insert(corrected)
                else raw(name).use { sql -> insert(sql, "memory_entry_corrections", corrected) }
                MemoryTextVersion(id, text, it, captured + 1000)
            }
            val ranges = source.getJSONArray("authorizedRanges")
            InformationExtractionSource.from(MemoryEntry(id, original, type, captured, zone, CaptureStatus.SAVED),
                textVersion, (0 until ranges.length()).map { j -> ranges.getJSONObject(j).let {
                    Utf16TextRange(it.getInt("startUtf16"), it.getInt("endUtf16")) } })
        }
        return InformationExtractionRequest(root.getString("requestId"), root.getString("extractionId"), sources)
    }
    private fun output(root: JSONObject) = InformationExtractionResult.Proposals(UnvalidatedProposalDocument("1.0.0",
        ExtractionOutcome.valueOf(root.getString("outcome")), root.toString()), ExtractionMode.MODEL_OUTPUT)
    private fun correlate(root: JSONObject, suffix: String): JSONObject = JSONObject(root.toString())
        .put("requestId", "request-$suffix").put("extractionId", "extraction-$suffix")

    @Test fun freshV4AndEmptyV3MigrationMatchExportedSchema() = runBlocking {
        for (legacy in listOf<Int?>(null, 3)) {
            val name = name()
            try {
                if (legacy != null) createLegacy(name, legacy)
                db(name).use { database -> assertTrue(database.informationProposalDao().findRelations().isEmpty()) }
                assertEquals("4", scalar(name, "PRAGMA user_version"))
                assertEquals("8", scalar(name, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('${(legacyTables + listOf("information_proposal_dedup_keys", "information_proposal_relations")).joinToString("','")}')"))
                assertIntegrity(name)
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun migrationFromV1AndV2PreservesOriginalsAndMetadata() = runBlocking {
        for (version in listOf(1, 2)) {
            val name = name()
            try {
                createLegacy(name, version)
                raw(name).use { insert(it, "memory_entries", MemoryEntryEntity("legacy-$version", "Original 🧪", 1L, "America/Lima", CaptureSourceType.TEXT)) }
                db(name).use { assertEquals("Original 🧪", it.memoryEntryDao().findById("legacy-$version")?.rawText) }
                assertEquals("4", scalar(name, "PRAGMA user_version")); assertIntegrity(name)
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun populatedV3BackfillPreservesEveryLegacyRowAndSurvivesReopen() = runBlocking {
        val name = name()
        try {
            createLegacy(name, 3)
            val root = fixture("02-rutina-semanal")
            val request = seed(null, name, root)
            val unopened = db(name)
            val prepared = RoomInformationProposalRepository(unopened.informationProposalDao()).prepareSubmission(request, output(root), 1) as PreparedProposalSubmission.Ready
            unopened.close()
            raw(name).use { sql -> prepared.batch.proposals.forEach {
                insert(sql, "information_proposals", it.proposal)
                insert(sql, "information_proposal_versions", it.version)
                it.sources.forEach { source -> insert(sql, "information_proposal_sources", source) }
                insert(sql, "information_proposal_decisions", it.decision)
            }
                insert(sql, "memory_entries", MemoryEntryEntity("extra", "Original sin cambios", 5L, "America/Lima", CaptureSourceType.VOICE))
                insert(sql, "memory_entry_corrections", MemoryEntryCorrectionEntity(77, "extra", "Corrección conservada", 6L))
            }
            val before = fingerprint(name)
            db(name).use { database ->
                val report = RoomProposalDeduplication(database.informationProposalDao()).backfill()
                assertEquals(1, report.indexed); assertEquals(0, report.unindexable)
                assertEquals(before, fingerprint(name))
            }
            db(name).use { database ->
                val changed = correlate(root, "legacy-retry")
                val retry = InformationExtractionRequest("request-legacy-retry", "extraction-legacy-retry", request.sources)
                val result = RoomProposalDeduplication(database.informationProposalDao()).submit(retry, output(changed), 2) as ProposalDeduplicationResult.Committed
                assertTrue(result.receipts.single().technicalDuplicate)
                assertEquals(before, fingerprint(name))
            }
            assertIntegrity(name)
        } finally { context.deleteDatabase(name) }
    }

    @Test fun ddlFailureRollsBackV3WithoutPartialTablesOrLostData() = runBlocking {
        val name = name()
        try {
            createLegacy(name, 3)
            raw(name).use { sql ->
                insert(sql, "memory_entries", MemoryEntryEntity("failure", "Conservar", 1L, "America/Lima", CaptureSourceType.TEXT))
                sql.execSQL("CREATE TABLE information_proposal_relations (blocker TEXT)")
            }
            val before = fingerprint(name)
            var failed = false
            try { db(name).use { it.memoryEntryDao().findById("failure") } } catch (_: Exception) { failed = true }
            assertTrue(failed); assertEquals(before, fingerprint(name))
            assertEquals("3", scalar(name, "PRAGMA user_version"))
            assertEquals("0", scalar(name, "SELECT COUNT(*) FROM sqlite_master WHERE name='information_proposal_dedup_keys'"))
        } finally { context.deleteDatabase(name) }
    }

    @Test fun concurrentDifferentExtractionsHaveOneDurableOwner() = runBlocking {
        val name = name()
        try {
            db(name).use { first ->
                val root = fixture("02-rutina-semanal")
                val request = seed(first, name, root)
                db(name).use { second ->
                    val results = (0 until 8).map { index -> async(Dispatchers.IO) {
                        val changed = correlate(root, "concurrent-$index")
                        val incoming = InformationExtractionRequest(changed.getString("requestId"), changed.getString("extractionId"), request.sources)
                        RoomProposalDeduplication((if (index % 2 == 0) first else second).informationProposalDao())
                            .submit(incoming, output(changed), index.toLong())
                    } }.awaitAll()
                    assertTrue(results.all { it is ProposalDeduplicationResult.Committed })
                    val receipts = results.flatMap { (it as ProposalDeduplicationResult.Committed).receipts }
                    assertEquals(1, receipts.count { !it.technicalDuplicate })
                    assertEquals(1, receipts.map { it.stored }.distinct().size)
                }
                assertEquals("1", scalar(name, "SELECT COUNT(*) FROM information_proposals"))
                assertEquals("1", scalar(name, "SELECT COUNT(*) FROM information_proposal_dedup_keys"))
                assertIntegrity(name)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun mixedBatchKeepsNewProposalsAndReturnsDuplicateReceiptWithReviewableContext() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val full = fixture("01-multiple")
            val single = correlate(full, "single").put("proposals", JSONArray().put(full.getJSONArray("proposals").getJSONObject(0)))
            val originalRequest = seed(database, name, single)
            val service = RoomProposalDeduplication(database.informationProposalDao())
            assertTrue(service.submit(originalRequest, output(single), 1) is ProposalDeduplicationResult.Committed)
            val changed = correlate(full, "mixed")
            val request = InformationExtractionRequest("request-mixed", "extraction-mixed", originalRequest.sources)
            val result = service.submit(request, output(changed), 2) as ProposalDeduplicationResult.Committed
            assertEquals(full.getJSONArray("proposals").length(), result.receipts.size)
            assertEquals(1, result.receipts.count { it.technicalDuplicate })
            for (receipt in result.receipts) assertTrue(RoomInformationProposalRepository(database.informationProposalDao())
                .find(receipt.stored.identity) is ProposalReviewLoadResult.Found)
            val repeated = service.submit(request, output(changed), 3) as ProposalDeduplicationResult.Committed
            assertTrue(repeated.receipts.all { it.technicalDuplicate })
            assertEquals(result.receipts.map { it.stored }, repeated.receipts.map { it.stored })
            assertEquals(full.getJSONArray("proposals").length().toString(), scalar(name, "SELECT COUNT(*) FROM information_proposals"))
            assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun simulatedHashCollisionRejectsWithoutDiscardingEitherOriginal() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val a = fixture("02-rutina-semanal"); val b = fixture("06-tarea-completada")
            val requestA = seed(database, name, a); val requestB = seed(database, name, b)
            val dao = database.informationProposalDao()
            val service = RoomProposalDeduplication(dao, RoomInformationProposalRepository(dao), ProposalCanonicalizer { "f".repeat(64) })
            assertTrue(service.submit(requestA, output(a), 1) is ProposalDeduplicationResult.Committed)
            val before = fingerprint(name)
            assertEquals(ProposalDeduplicationRejection.HASH_COLLISION,
                (service.submit(requestB, output(b), 2) as ProposalDeduplicationResult.Rejected).reason)
            assertEquals(before, fingerprint(name)); assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun changedSourcesAndStaleVersionFailClosed() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val root = fixture("02-rutina-semanal"); val request = seed(database, name, root)
            val service = RoomProposalDeduplication(database.informationProposalDao())
            val receipt = (service.submit(request, output(root), 1) as ProposalDeduplicationResult.Committed).receipts.single()
            database.memoryCorrectionDao().insert(MemoryEntryCorrectionEntity(0, "s-weekly", "Nueva corrección sintética", 2))
            assertEquals(ProposalDeduplicationRejection.SOURCE_CHANGED,
                (service.submit(request, output(root), 2) as ProposalDeduplicationResult.Rejected).reason)
            assertTrue(service.compareAndRelate(receipt.stored, receipt.stored.copy(version = 2), 3) is ProposalRelationResult.Rejected)
            assertEquals("1", scalar(name, "SELECT COUNT(*) FROM information_proposals"))
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun technicalKeyNeverReusesAnObsoleteUserEditedVersion() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val root = fixture("06-tarea-completada"); val request = seed(database, name, root)
            val dao = database.informationProposalDao(); val repository = RoomInformationProposalRepository(dao)
            val service = RoomProposalDeduplication(dao, repository)
            val owner = (service.submit(request, output(root), 1) as ProposalDeduplicationResult.Committed).receipts.single().stored
            val review = (repository.find(owner.identity) as ProposalReviewLoadResult.Found).review
            val edited = JSONObject(review.document.json)
            edited.getJSONArray("proposals").getJSONObject(0).getJSONObject("data").getJSONObject("title")
                .put("value", "Informe revisado por usuario")
            assertTrue(repository.appendUserRevision(owner.identity, review.request, output(edited).document,
                ExplicitUserReviewAction.afterExplicitReview("edit-title"), 2) is ProposalPersistenceResult.Applied)
            assertEquals(ProposalDeduplicationRejection.STALE_VERSION,
                (service.submit(request, output(root), 3) as ProposalDeduplicationResult.Rejected).reason)
            assertEquals("2", scalar(name, "SELECT COUNT(*) FROM information_proposal_versions"))
            assertEquals("Ya terminé el informe.", database.memoryEntryDao().findById("s-completed")?.rawText)
            assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    private suspend fun updateSetup(database: YezviDatabase, name: String, ambiguousException: Boolean = false): Triple<ProposalVersionReference, InformationExtractionRequest, JSONObject> {
        val after = correlate(fixture("02-rutina-semanal"), "update")
        val current = after.getJSONArray("proposals").getJSONObject(0)
        if (!ambiguousException) current.getJSONObject("data").remove("exceptions")
        val previousNode = JSONObject(current.toString()).put("proposalId", "previous")
        previousNode.getJSONObject("data").remove("exceptions")
        previousNode.getJSONObject("data").getJSONObject("recurrence").getJSONArray("weekdays").remove(1)
        val before = correlate(after, "before").put("proposals", JSONArray().put(previousNode))
        val request = seed(database, name, before)
        val repository = RoomInformationProposalRepository(database.informationProposalDao())
        val initial = repository.submitForReview(request, output(before), 1)
        assertTrue(initial.toString(), initial is ProposalPersistenceResult.BatchApplied)
        val identity = ProposalIdentity(request.extractionId, "previous")
        val review = (repository.find(identity) as ProposalReviewLoadResult.Found).review
        assertTrue(repository.transition(identity, review.request, UnvalidatedProposalDocument("1.0.0", review.document.outcome, review.document.json), PersistedProposalAction.CONFIRM,
            ExplicitUserReviewAction.afterExplicitReview("confirm-previous"), 2) is ProposalPersistenceResult.Applied)
        val frequency = current.getJSONObject("data").getJSONObject("recurrence").getJSONObject("frequency")
        current.put("relations", JSONArray().put(JSONObject().put("value", JSONObject().put("kind", "UPDATES")
            .put("targetProposalId", "previous")).put("evidence", frequency.getJSONArray("evidence"))
            .put("assessment", frequency.getJSONObject("assessment"))))
        after.put("proposals", JSONArray().put(previousNode).put(current))
        return Triple(ProposalVersionReference(identity, 1), InformationExtractionRequest("request-update", "extraction-update", request.sources), after)
    }

    @Test fun hostUpdateIsNewPendingProposalWithAtomicDirectedVersionedLink() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val (previous, request, root) = updateSetup(database, name)
            val service = RoomProposalDeduplication(database.informationProposalDao())
            val result = service.proposeHostUpdate(previous, request, output(root), "estudio", 3) as ProposalRelationResult.Evaluated
            assertEquals(ProposalComparisonKind.UPDATE_PROPOSED, result.kind)
            val link = database.informationProposalDao().findRelations().single()
            assertEquals(previous.identity.extractionId, link.fromExtractionId)
            assertEquals("extraction-update", link.toExtractionId)
            assertEquals("CONFIRMED", database.informationProposalDao().findProposal(previous.identity.extractionId, "previous")?.reviewState)
            assertEquals("PROPOSED", database.informationProposalDao().findProposal("extraction-update", "estudio")?.reviewState)
            val retry = service.proposeHostUpdate(previous, request, output(root), "estudio", 4) as ProposalRelationResult.Evaluated
            assertTrue(retry.alreadyRecorded); assertEquals(result.relationId, retry.relationId)
            assertEquals(1, database.informationProposalDao().findRelations().size)
            assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun failedHostLinkRollsBackNewProposalKeysSourcesAndDecisions() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val (previous, request, root) = updateSetup(database, name)
            val before = fingerprint(name)
            raw(name).use { it.execSQL("CREATE TRIGGER f409_block BEFORE INSERT ON information_proposal_relations BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END") }
            assertTrue(RoomProposalDeduplication(database.informationProposalDao())
                .proposeHostUpdate(previous, request, output(root), "estudio", 3) is ProposalRelationResult.Rejected)
            assertEquals(before, fingerprint(name))
            assertEquals("1", scalar(name, "SELECT COUNT(*) FROM information_proposal_dedup_keys"))
            assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun ambiguousExceptionRemainsConflictWithoutInventingDatesOrChangingConfirmedRoutine() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val (previous, request, root) = updateSetup(database, name, ambiguousException = true)
            val result = RoomProposalDeduplication(database.informationProposalDao())
                .proposeHostUpdate(previous, request, output(root), "estudio", 3) as ProposalRelationResult.Evaluated
            assertEquals(ProposalComparisonKind.CONFLICT_REQUIRES_REVIEW, result.kind)
            assertEquals("CONFIRMED", database.informationProposalDao().findProposal(previous.identity.extractionId, "previous")?.reviewState)
            assertEquals("PROPOSED", database.informationProposalDao().findProposal("extraction-update", "estudio")?.reviewState)
            assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun deleteAndFailedDeletePreserveForeignKeysAndDoNotLeaveOrphans() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val (previous, request, root) = updateSetup(database, name)
            val service = RoomProposalDeduplication(database.informationProposalDao())
            assertTrue(service.proposeHostUpdate(previous, request, output(root), "estudio", 3) is ProposalRelationResult.Evaluated)
            val before = fingerprint(name)
            raw(name).use { it.execSQL("CREATE TRIGGER f409_block_delete BEFORE DELETE ON memory_entries BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END") }
            var failed = false
            try { database.memoryEntryDao().deleteById("s-weekly") } catch (_: Exception) { failed = true }
            assertTrue(failed); assertEquals(before, fingerprint(name)); assertEquals(1, database.informationProposalDao().findRelations().size)
            raw(name).use { it.execSQL("DROP TRIGGER f409_block_delete") }
            assertEquals(1, database.memoryEntryDao().deleteByIds(listOf("s-weekly")))
            assertEquals("0", scalar(name, "SELECT COUNT(*) FROM information_proposal_dedup_keys"))
            assertEquals("0", scalar(name, "SELECT COUNT(*) FROM information_proposal_relations"))
            assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun selfBrokenAndObsoleteRelationsAreRejectedWithoutRows() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val root = fixture("02-rutina-semanal"); val request = seed(database, name, root)
            val dao = database.informationProposalDao(); val service = RoomProposalDeduplication(dao)
            val owner = (service.submit(request, output(root), 1) as ProposalDeduplicationResult.Committed).receipts.single().stored
            assertTrue(service.compareAndRelate(owner, owner, 2) is ProposalRelationResult.Rejected)
            val missing = ProposalVersionReference(ProposalIdentity("extraction-missing", "missing"), 1)
            assertTrue(service.compareAndRelate(owner, missing, 2) is ProposalRelationResult.Rejected)
            var rejected = false
            try { dao.insertRelation(relationEntity(owner, missing, ProposalComparisonKind.POSSIBLE_EQUIVALENT, "a", "b", 2)) }
            catch (_: Exception) { rejected = true }
            assertTrue(rejected); assertTrue(dao.findRelations().isEmpty()); assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun unindexableLegacyPayloadIsPreservedWithoutInventingIdentity() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val root = fixture("02-rutina-semanal"); val request = seed(database, name, root)
            val repo = RoomInformationProposalRepository(database.informationProposalDao())
            val prepared = repo.prepareSubmission(request, output(root), 1) as PreparedProposalSubmission.Ready
            assertEquals(ProposalDaoWriteResult.APPLIED, database.informationProposalDao().insertInitialBatch(prepared.batch))
            raw(name).use { it.execSQL("UPDATE information_proposal_versions SET payloadSha256='unverifiable-synthetic-digest'") }
            val before = fingerprint(name)
            val report = RoomProposalDeduplication(database.informationProposalDao()).backfill()
            assertEquals(1, report.unindexable); assertEquals(0, report.indexed)
            assertEquals(before, fingerprint(name)); assertEquals("0", scalar(name, "SELECT COUNT(*) FROM information_proposal_dedup_keys"))
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun duplicatesInsideOneBatchDoNotLoseNewMembersOrCreateExtraOwners() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val root = fixture("06-tarea-completada")
            val nodes = root.getJSONArray("proposals")
            nodes.put(JSONObject(nodes.getJSONObject(0).toString()).put("proposalId", "duplicate-task"))
            val request = seed(database, name, root)
            val result = RoomProposalDeduplication(database.informationProposalDao()).submit(request, output(root), 1) as ProposalDeduplicationResult.Committed
            assertEquals(2, result.receipts.size); assertEquals(1, result.receipts.count { it.technicalDuplicate })
            assertEquals(1, result.receipts.map { it.stored }.distinct().size)
            assertEquals("1", scalar(name, "SELECT COUNT(*) FROM information_proposals")); assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun homonymousProposalsFromDifferentSourcesStaySeparate() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val original = fixture("06-tarea-completada")
            val request = seed(database, name, original)
            val service = RoomProposalDeduplication(database.informationProposalDao())
            val left = (service.submit(request, output(original), 1) as ProposalDeduplicationResult.Committed).receipts.single().stored
            val changed = correlate(JSONObject(original.toString().replace("s-completed", "s-synthetic-copy")), "same-title")
            val copy = MemoryEntry("s-synthetic-copy", "Ya terminé el informe.", CaptureSourceType.TEXT,
                request.sources.single().snapshot.capturedAtEpochMillis, "America/Lima", CaptureStatus.SAVED)
            database.memoryEntryDao().insert(MemoryEntryEntity(copy.id, copy.rawText, copy.capturedAtEpochMillis, copy.timezoneId, copy.sourceType))
            val newRequest = InformationExtractionRequest("request-same-title", "extraction-same-title", listOf(InformationExtractionSource.from(copy)))
            val right = (service.submit(newRequest, output(changed), 2) as ProposalDeduplicationResult.Committed).receipts.single()
            assertFalse(right.technicalDuplicate)
            assertEquals(ProposalComparisonKind.DISTINCT, (service.compareAndRelate(left, right.stored, 3) as ProposalRelationResult.Evaluated).kind)
            assertEquals("2", scalar(name, "SELECT COUNT(*) FROM information_proposals")); assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun equivalentCandidateIsSymmetricPreservesMultipleSourcesAndNeverConfirms() = runBlocking {
        val name = name()
        try { db(name).use { database ->
            val original = fixture("06-tarea-completada"); val request = seed(database, name, original)
            val service = RoomProposalDeduplication(database.informationProposalDao())
            val left = (service.submit(request, output(original), 1) as ProposalDeduplicationResult.Committed).receipts.single().stored
            val changed = correlate(original, "multi-source")
            val copy = MemoryEntry("s-synthetic-copy", "Ya terminé el informe.", CaptureSourceType.TEXT,
                request.sources.single().snapshot.capturedAtEpochMillis, "America/Lima", CaptureStatus.SAVED)
            database.memoryEntryDao().insert(MemoryEntryEntity(copy.id, copy.rawText, copy.capturedAtEpochMillis, copy.timezoneId, copy.sourceType))
            val snapshots = changed.getJSONArray("sourceSnapshots")
            snapshots.put(JSONObject(snapshots.getJSONObject(0).toString().replace("s-completed", copy.id)))
            val evidence = changed.getJSONArray("proposals").getJSONObject(0).getJSONObject("data").getJSONObject("state").getJSONArray("evidence")
            evidence.put(JSONObject(evidence.getJSONObject(0).toString().replace("s-completed", copy.id)))
            val newRequest = InformationExtractionRequest("request-multi-source", "extraction-multi-source",
                request.sources + InformationExtractionSource.from(copy))
            val right = (service.submit(newRequest, output(changed), 2) as ProposalDeduplicationResult.Committed).receipts.single().stored
            val first = service.compareAndRelate(left, right, 3) as ProposalRelationResult.Evaluated
            val second = service.compareAndRelate(right, left, 4) as ProposalRelationResult.Evaluated
            assertEquals(ProposalComparisonKind.POSSIBLE_EQUIVALENT, first.kind)
            assertEquals(first.relationId, second.relationId); assertTrue(second.alreadyRecorded)
            assertEquals(1, database.informationProposalDao().findRelations().size)
            assertEquals(2, database.informationProposalDao().findSources(right.identity.extractionId, right.identity.proposalId, 1).size)
            assertEquals("PROPOSED", database.informationProposalDao().findProposal(left.identity.extractionId, left.identity.proposalId)?.reviewState)
            assertEquals(1, database.memoryEntryDao().deleteById(copy.id))
            assertTrue(database.informationProposalDao().findRelations().isEmpty())
            assertNotNull(database.informationProposalDao().findProposal(left.identity.extractionId, left.identity.proposalId))
            assertIntegrity(name)
        } } finally { context.deleteDatabase(name) }
    }

    /** Invoked in its OWN instrumentation process, followed by processRestartReadAndRetry. */
    @Test fun processRestartWrite() = runBlocking {
        val name = "f409-process-restart-synthetic.db"
        check(!context.getDatabasePath(name).exists())
        val database = db(name)
        val root = fixture("02-rutina-semanal"); val request = seed(database, name, root)
        assertTrue(RoomProposalDeduplication(database.informationProposalDao()).submit(request, output(root), 1) is ProposalDeduplicationResult.Committed)
        instrumentation.sendStatus(0, Bundle().apply { putString("f409ProcessPid", android.os.Process.myPid().toString()) })
        // Deliberately leave the synthetic connection open; instrumentation termination ends this process.
    }

    @Test fun processRestartReadAndRetry() = runBlocking {
        val name = "f409-process-restart-synthetic.db"
        check(context.getDatabasePath(name).exists())
        try { db(name).use { database ->
            val root = correlate(fixture("02-rutina-semanal"), "process-retry")
            val request = seed(database, name, root)
            val result = RoomProposalDeduplication(database.informationProposalDao()).submit(request, output(root), 2) as ProposalDeduplicationResult.Committed
            assertTrue(result.receipts.single().technicalDuplicate)
            assertEquals("1", scalar(name, "SELECT COUNT(*) FROM information_proposals")); assertIntegrity(name)
            instrumentation.sendStatus(0, Bundle().apply { putString("f409ProcessPid", android.os.Process.myPid().toString()) })
        } } finally { context.deleteDatabase(name) }
    }
}
