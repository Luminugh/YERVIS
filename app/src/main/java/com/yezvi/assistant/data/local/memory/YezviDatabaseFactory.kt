package com.yezvi.assistant.data.local.memory

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver

/** Provides a process-wide Room instance without retaining an Activity context. */
object YezviDatabaseFactory {
    const val DATABASE_NAME = "yezvi.db"

    @Volatile
    private var instance: YezviDatabase? = null

    fun getInstance(context: Context): YezviDatabase =
        instance ?: synchronized(this) {
            instance ?: create(context, DATABASE_NAME).also { instance = it }
        }

    internal fun create(context: Context, databaseName: String): YezviDatabase =
        Room.databaseBuilder<YezviDatabase>(
            context.applicationContext,
            databaseName,
        )
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
}
