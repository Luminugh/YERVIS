package com.yezvi.assistant.data.local.memory

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL

/** Adds only F4.08 derived-data tables. Existing v1/v2 memory tables are never rewritten. */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.executeSQL(
            "CREATE TABLE `information_proposals` (" +
                "`extractionId` TEXT NOT NULL, `proposalId` TEXT NOT NULL, `requestId` TEXT NOT NULL, " +
                "`currentVersion` INTEGER NOT NULL, `reviewState` TEXT NOT NULL, " +
                "`createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`extractionId`, `proposalId`))",
        )
        connection.executeSQL(
            "CREATE INDEX `index_information_proposals_reviewState_updatedAtEpochMillis` " +
                "ON `information_proposals` (`reviewState`, `updatedAtEpochMillis`)",
        )
        connection.executeSQL(
            "CREATE TABLE `information_proposal_versions` (" +
                "`extractionId` TEXT NOT NULL, `proposalId` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`requestId` TEXT NOT NULL, `schemaVersion` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
                "`payloadJson` TEXT NOT NULL, `payloadSha256` TEXT NOT NULL, `createdBy` TEXT NOT NULL, " +
                "`actionId` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`extractionId`, `proposalId`, `version`), " +
                "FOREIGN KEY(`extractionId`, `proposalId`) REFERENCES `information_proposals`(" +
                "`extractionId`, `proposalId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        connection.executeSQL(
            "CREATE UNIQUE INDEX `index_information_proposal_versions_extractionId_proposalId_actionId` " +
                "ON `information_proposal_versions` (`extractionId`, `proposalId`, `actionId`)",
        )
        connection.executeSQL(
            "CREATE TABLE `information_proposal_sources` (" +
                "`extractionId` TEXT NOT NULL, `proposalId` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                "`sourceEntryId` TEXT NOT NULL, `revisionKind` TEXT NOT NULL, `revisionId` TEXT NOT NULL, " +
                "`sourceType` TEXT NOT NULL, `capturedAtEpochMillis` INTEGER NOT NULL, " +
                "`timezoneId` TEXT NOT NULL, `textSha256` TEXT NOT NULL, `textLengthUtf16` INTEGER NOT NULL, " +
                "`authorizedRangesJson` TEXT NOT NULL, " +
                "PRIMARY KEY(`extractionId`, `proposalId`, `version`, `sourceEntryId`, `revisionKind`, `revisionId`), " +
                "FOREIGN KEY(`extractionId`, `proposalId`, `version`) REFERENCES `information_proposal_versions`(" +
                "`extractionId`, `proposalId`, `version`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`sourceEntryId`) REFERENCES `memory_entries`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE RESTRICT)",
        )
        connection.executeSQL(
            "CREATE INDEX `index_information_proposal_sources_extractionId_proposalId_version` " +
                "ON `information_proposal_sources` (`extractionId`, `proposalId`, `version`)",
        )
        connection.executeSQL(
            "CREATE INDEX `index_information_proposal_sources_sourceEntryId` " +
                "ON `information_proposal_sources` (`sourceEntryId`)",
        )
        connection.executeSQL(
            "CREATE TABLE `information_proposal_decisions` (" +
                "`extractionId` TEXT NOT NULL, `proposalId` TEXT NOT NULL, `actionId` TEXT NOT NULL, " +
                "`sequence` INTEGER NOT NULL, `version` INTEGER NOT NULL, `action` TEXT NOT NULL, `actor` TEXT NOT NULL, " +
                "`fromState` TEXT NOT NULL, `toState` TEXT NOT NULL, `occurredAtEpochMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`extractionId`, `proposalId`, `actionId`), " +
                "FOREIGN KEY(`extractionId`, `proposalId`) REFERENCES `information_proposals`(" +
                "`extractionId`, `proposalId`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`extractionId`, `proposalId`, `version`) REFERENCES `information_proposal_versions`(" +
                "`extractionId`, `proposalId`, `version`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        connection.executeSQL(
            "CREATE INDEX `index_information_proposal_decisions_extractionId_proposalId_version` " +
                "ON `information_proposal_decisions` (`extractionId`, `proposalId`, `version`)",
        )
        connection.executeSQL(
            "CREATE INDEX `index_information_proposal_decisions_extractionId_proposalId_occurredAtEpochMillis` " +
                "ON `information_proposal_decisions` (`extractionId`, `proposalId`, `occurredAtEpochMillis`)",
        )
        connection.executeSQL(
            "CREATE UNIQUE INDEX `index_information_proposal_decisions_extractionId_proposalId_sequence` " +
                "ON `information_proposal_decisions` (`extractionId`, `proposalId`, `sequence`)",
        )
    }
}
