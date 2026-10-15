package com.yezvi.assistant.data.local.memory

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        MemoryEntryEntity::class,
        MemoryEntryCorrectionEntity::class,
        InformationProposalEntity::class,
        InformationProposalVersionEntity::class,
        InformationProposalSourceEntity::class,
        InformationProposalDecisionEntity::class,
        InformationProposalDedupKeyEntity::class,
        InformationProposalRelationEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class YezviDatabase : RoomDatabase() {
    abstract fun memoryEntryDao(): MemoryEntryDao

    abstract fun memoryCorrectionDao(): MemoryCorrectionDao

    abstract fun informationProposalDao(): InformationProposalDao
}
