package com.yezvi.assistant.data.speech

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only aggregate evidence. Never exports notes, individual hashes or audio. */
class VoiceDataIntegrityProbeTest {
    @Test fun reportReadOnlyDatabaseFingerprint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = instrumentation.targetContext.getDatabasePath("yezvi.db")
        assertTrue("Expected existing normal database; never create one for the probe", file.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(bytes: ByteArray) {
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        var rows = 0
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or
            SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            assertTrue(db.isReadOnly)
            add(db.version.toString().toByteArray())
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            for (table in tables) {
                add(table.toByteArray())
                val quoted = table.replace("\"", "\"\"")
                val columns = db.rawQuery("SELECT * FROM \"$quoted\" LIMIT 0", null).use { it.columnCount }
                val order = (1..columns).joinToString(",")
                db.rawQuery("SELECT * FROM \"$quoted\" ORDER BY $order", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows++
                        repeat(cursor.columnCount) { index ->
                            val type = cursor.getType(index)
                            add(type.toString().toByteArray())
                            add(when (type) {
                                Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                                else -> cursor.getString(index).toByteArray(Charsets.UTF_8)
                            })
                        }
                    }
                }
            }
        }
        instrumentation.addResults(Bundle().apply {
            putString("f312Digest", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            putInt("f312Rows", rows)
        })
    }
}
