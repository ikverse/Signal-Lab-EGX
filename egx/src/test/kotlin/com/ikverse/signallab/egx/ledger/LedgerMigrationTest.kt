package com.ikverse.signallab.egx.ledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class LedgerMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A phone on 0.1.x: the ledger built from Room's own version-1 schema, with one copy the owner saved. */
    @Test
    fun versionOneKeepsItsRowsAndGainsTheAnalystsAnswers() = runBlocking {
        val name = "egx-ledger-migration-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.ikverse.signallab.egx.ledger.EgxLedgerDatabase/1.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '${schema.getString("identityHash")}')")
            db.execSQL("INSERT INTO copy (strategy, code, buy, day, shares, price, note) VALUES ('calm', 'COMI', 1, 0, 100, 97.5, '')")
            db.version = 1
        }
        val ledger = EgxLedgerDatabase.open(context, name)
        val dao = ledger.dao()
        assertEquals("COMI", dao.copies().first().single().code)
        dao.addAnswer(AnalystAnswerRow(at = 1, question = "q", answer = "a", ending = "done"))
        assertEquals("a", dao.answers().first().single().answer)
        ledger.close()
    }
}
