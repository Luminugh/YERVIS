package com.yezvi.assistant.data.local.memory

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.async.executeSQL

/** DDL only. Exact legacy backfill is explicit, bounded in pages, and never edits v3 history. */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.executeSQL("CREATE TABLE `information_proposal_dedup_keys` (" +
            "`canonicalizationVersion` INTEGER NOT NULL, `digest` TEXT NOT NULL, `descriptor` TEXT NOT NULL, " +
            "`extractionId` TEXT NOT NULL, `proposalId` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
            "PRIMARY KEY(`canonicalizationVersion`, `digest`), " +
            "FOREIGN KEY(`extractionId`, `proposalId`, `version`) REFERENCES `information_proposal_versions`" +
            "(`extractionId`, `proposalId`, `version`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.executeSQL("CREATE UNIQUE INDEX `index_information_proposal_dedup_keys_extractionId_proposalId_version_canonicalizationVersion` " +
            "ON `information_proposal_dedup_keys` (`extractionId`, `proposalId`, `version`, `canonicalizationVersion`)")
        connection.executeSQL("CREATE TABLE `information_proposal_relations` (" +
            "`relationId` TEXT NOT NULL, `fromExtractionId` TEXT NOT NULL, `fromProposalId` TEXT NOT NULL, `fromVersion` INTEGER NOT NULL, " +
            "`toExtractionId` TEXT NOT NULL, `toProposalId` TEXT NOT NULL, `toVersion` INTEGER NOT NULL, " +
            "`kind` TEXT NOT NULL, `comparatorVersion` INTEGER NOT NULL, `evidenceDigest` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, " +
            "PRIMARY KEY(`relationId`), " +
            "FOREIGN KEY(`fromExtractionId`, `fromProposalId`, `fromVersion`) REFERENCES `information_proposal_versions`" +
            "(`extractionId`, `proposalId`, `version`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
            "FOREIGN KEY(`toExtractionId`, `toProposalId`, `toVersion`) REFERENCES `information_proposal_versions`" +
            "(`extractionId`, `proposalId`, `version`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.executeSQL("CREATE INDEX `index_information_proposal_relations_fromExtractionId_fromProposalId_fromVersion` " +
            "ON `information_proposal_relations` (`fromExtractionId`, `fromProposalId`, `fromVersion`)")
        connection.executeSQL("CREATE INDEX `index_information_proposal_relations_toExtractionId_toProposalId_toVersion` " +
            "ON `information_proposal_relations` (`toExtractionId`, `toProposalId`, `toVersion`)")
        connection.executeSQL("CREATE UNIQUE INDEX `index_information_proposal_relations_comparatorVersion_kind_fromExtractionId_fromProposalId_fromVersion_toExtractionId_toProposalId_toVersion` " +
            "ON `information_proposal_relations` (`comparatorVersion`, `kind`, `fromExtractionId`, `fromProposalId`, `fromVersion`, " +
            "`toExtractionId`, `toProposalId`, `toVersion`)")
    }
}
