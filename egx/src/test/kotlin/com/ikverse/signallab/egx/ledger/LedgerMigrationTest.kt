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

    /** A phone on 0.2.x: version 2 with a paper day, a call, a copy and an answer, opened by the app with the technical picks. */
    @Test
    fun versionTwoKeepsEveryRowAndGainsThePicksTables() = runBlocking {
        val name = "egx-ledger-migration-v2-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.ikverse.signallab.egx.ledger.EgxLedgerDatabase/2.json").readText()).getJSONObject("database")
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
            db.execSQL("INSERT INTO paper_day (strategy, record, day, equity, held, dividends) VALUES ('egx_calm', 'levels', 100, 33000.0, 0.5, 0.0)")
            db.execSQL("INSERT INTO copy (strategy, code, buy, day, shares, price, note) VALUES ('egx_calm', 'COMI', 1, 0, 100, 97.5, '')")
            db.execSQL("INSERT INTO analyst_answer (at, question, answer, ending) VALUES (1, 'q', 'a', 'done')")
            db.version = 2
        }
        val ledger = EgxLedgerDatabase.open(context, name)
        val dao = ledger.dao()
        assertEquals(33000.0, dao.days("egx_calm", Records.LEVELS).single().equity)
        assertEquals("COMI", dao.copies().first().single().code)
        assertEquals("a", dao.answers().first().single().answer)
        dao.addPicks(listOf(PickRow(100, "egx_short_term", "egx_short_h20_v1_d20", "COMI", 1, 128.8, 2.1, 127.8, 128.8, 124.0, 133.0, 137.5, 20, null, 127.8, 0.97, 1.4, 0.0, true, lossMaking = null)))
        dao.addPickEvents(listOf(PickEventRow(101, "egx_short_term", "COMI", 100, "bought", 128.5)))
        dao.putFacts(FactsRow("COMI", 5, 7.2, 61e9, 17.9, 32e9, 0.13))
        dao.addHeadlines(listOf(HeadlineRow("u", "COMI", "h", 99, "results", 5)))
        dao.addMarketDays(listOf(MarketDayRow(100, 41000.0, true, null, 40, 80)))
        assertEquals("COMI", dao.picks().first().single().code)
        assertEquals("bought", dao.pickEvents().first().single().kind)
        assertEquals(0.13, dao.facts("COMI")!!.debtToEquity)
        assertEquals("h", dao.headlines("COMI").single().headline)
        assertEquals(null, dao.lastMarket().first()!!.above200)
        ledger.close()
    }
}
