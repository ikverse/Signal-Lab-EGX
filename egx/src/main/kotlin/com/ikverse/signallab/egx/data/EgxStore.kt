package com.ikverse.signallab.egx.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where a stock's daily download stands. */
data class EgxSyncState(
    val code: String,
    /** When the whole history was last downloaded (ms); 0 never. */
    val fullAt: Long,
    /** The last stored session. */
    val lastDay: Long?,
    /** The newest split Yahoo had recorded at the last full download: a newer one means Yahoo re-scaled the history. */
    val lastSplitDay: Long?,
    /** Yahoo had no legacy feed for it when last asked (ms); 0 when it had. */
    val missingAt: Long = 0,
)

/**
 * The Egyptian data on the phone, in its own file (`egx.db`): cleaned daily rows, each session's hourly open, the EGX30,
 * Yahoo's dividend and split tables, and the dividend list. Everything but the dividend list can be downloaded again;
 * the dividend list holds the owner's hand entries and is never cleared.
 *
 * Android 10 ships SQLite 3.22 (no UPSERT), so writes are `INSERT OR REPLACE` on primary keys.
 */
class EgxStore(
    context: Context?,
    name: String? = "egx.db",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SQLiteOpenHelper(context, name, null, VERSION) {
    private val lock = Mutex()

    override fun onConfigure(db: SQLiteDatabase) {
        if (databaseName != null) setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE daily (
                code TEXT NOT NULL, day INTEGER NOT NULL,
                high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL,
                PRIMARY KEY (code, day)
            ) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE session_open (
                code TEXT NOT NULL, day INTEGER NOT NULL, open REAL NOT NULL, last_close REAL NOT NULL,
                PRIMARY KEY (code, day)
            ) WITHOUT ROWID
        """.trimIndent())
        db.execSQL("CREATE TABLE egx30 (day INTEGER PRIMARY KEY, open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL)")
        db.execSQL("CREATE TABLE yahoo_dividend (code TEXT NOT NULL, ex_day INTEGER NOT NULL, amount REAL NOT NULL, PRIMARY KEY (code, ex_day)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE yahoo_split (code TEXT NOT NULL, day INTEGER NOT NULL, ratio REAL NOT NULL, PRIMARY KEY (code, day)) WITHOUT ROWID")
        db.execSQL("""
            CREATE TABLE sync_state (
                code TEXT PRIMARY KEY, full_at INTEGER NOT NULL, last_day INTEGER, last_split_day INTEGER,
                missing_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE dividend (
                id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL, amount REAL,
                assembly_day INTEGER, payment_day INTEGER, last_cum_day INTEGER, announced_day INTEGER, last_cum_estimated INTEGER NOT NULL,
                source TEXT NOT NULL, by_hand INTEGER NOT NULL, cancelled INTEGER NOT NULL,
                yahoo_ex_day INTEGER, yahoo_amount REAL, check_state TEXT NOT NULL,
                headline TEXT NOT NULL, url TEXT NOT NULL, updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX dividend_code ON dividend (code)")
        createMacro(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Every version keeps the dividend table: it holds the owner's own entries. Version 2 adds the macro table.
        if (oldVersion < 2) createMacro(db)
    }

    private fun createMacro(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE IF NOT EXISTS macro (symbol TEXT NOT NULL, day INTEGER NOT NULL, close REAL NOT NULL, PRIMARY KEY (symbol, day)) WITHOUT ROWID")

    private suspend fun <T> access(block: (SQLiteDatabase) -> T): T =
        lock.withLock { withContext(io) { block(writableDatabase) } }

    private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try {
            val r = block()
            setTransactionSuccessful()
            return r
        } finally {
            endTransaction()
        }
    }

    // --- daily rows ----------------------------------------------------------------

    /** Stores [rows] for [code]; with [replaceAll] the stock's earlier rows are dropped first (a full download). */
    suspend fun saveDaily(code: String, rows: List<DailyRow>, replaceAll: Boolean = false) = access { db ->
        db.inTransaction {
            if (replaceAll) delete("daily", "code=?", arrayOf(code))
            val stmt = compileStatement("INSERT OR REPLACE INTO daily (code, day, high, low, close, volume) VALUES (?,?,?,?,?,?)")
            for (r in rows) {
                stmt.bindString(1, code); stmt.bindLong(2, r.day); stmt.bindDouble(3, r.high); stmt.bindDouble(4, r.low)
                stmt.bindDouble(5, r.close); stmt.bindDouble(6, r.volume)
                stmt.executeInsert()
                stmt.clearBindings()
            }
        }
    }

    /** A stock's stored rows from [fromDay] on, oldest first. */
    suspend fun daily(code: String, fromDay: Long = Long.MIN_VALUE): List<DailyRow> = access { db ->
        db.rawQuery("SELECT day, high, low, close, volume FROM daily WHERE code=? AND day>=? ORDER BY day",
            arrayOf(code, fromDay.toString())).use { c ->
            List(c.count) { c.moveToNext(); DailyRow(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4)) }
        }
    }

    /** Every stock's first stored session. */
    suspend fun firstDays(): Map<String, Long> = access { db ->
        db.rawQuery("SELECT code, MIN(day) FROM daily GROUP BY code", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getLong(1)) }
        }
    }

    /** How many stocks traded on each stored day, and how many stocks are stored: the calendar's input. */
    suspend fun tradedCounts(): Pair<Map<Long, Int>, Int> = access { db ->
        val counts = db.rawQuery("SELECT day, COUNT(*) FROM daily WHERE volume>0 GROUP BY day", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1)) }
        }
        val stocks = db.rawQuery("SELECT COUNT(DISTINCT code) FROM daily", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        counts to stocks
    }

    // --- hourly opens --------------------------------------------------------------

    suspend fun saveOpens(code: String, opens: Collection<SessionOpen>) = access { db ->
        db.inTransaction {
            val stmt = compileStatement("INSERT OR REPLACE INTO session_open (code, day, open, last_close) VALUES (?,?,?,?)")
            for (o in opens) {
                stmt.bindString(1, code); stmt.bindLong(2, o.day); stmt.bindDouble(3, o.open); stmt.bindDouble(4, o.lastClose)
                stmt.executeInsert()
                stmt.clearBindings()
            }
        }
    }

    suspend fun opens(code: String, fromDay: Long = Long.MIN_VALUE): Map<Long, SessionOpen> = access { db ->
        db.rawQuery("SELECT day, open, last_close FROM session_open WHERE code=? AND day>=?", arrayOf(code, fromDay.toString())).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), SessionOpen(c.getLong(0), c.getDouble(1), c.getDouble(2))) }
        }
    }

    // --- EGX30 ---------------------------------------------------------------------

    suspend fun saveEgx30(days: List<IndexDay>) = access { db ->
        db.inTransaction {
            val stmt = compileStatement("INSERT OR REPLACE INTO egx30 (day, open, high, low, close) VALUES (?,?,?,?,?)")
            for (d in days) {
                stmt.bindLong(1, d.day); stmt.bindDouble(2, d.open); stmt.bindDouble(3, d.high); stmt.bindDouble(4, d.low)
                stmt.bindDouble(5, d.close)
                stmt.executeInsert()
                stmt.clearBindings()
            }
        }
    }

    suspend fun egx30(): List<IndexDay> = access { db ->
        db.rawQuery("SELECT day, open, high, low, close FROM egx30 ORDER BY day", null).use { c ->
            List(c.count) { c.moveToNext(); IndexDay(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4)) }
        }
    }

    // --- the dollar and gold ([Macro]) ------------------------------------------------

    suspend fun saveMacro(symbol: String, closes: List<Pair<Long, Double>>) = access { db ->
        db.inTransaction {
            val stmt = compileStatement("INSERT OR REPLACE INTO macro (symbol, day, close) VALUES (?,?,?)")
            for ((day, close) in closes) {
                stmt.bindString(1, symbol); stmt.bindLong(2, day); stmt.bindDouble(3, close)
                stmt.executeInsert()
                stmt.clearBindings()
            }
        }
    }

    /** A macro series, oldest first. */
    suspend fun macro(symbol: String): List<Pair<Long, Double>> = access { db ->
        db.rawQuery("SELECT day, close FROM macro WHERE symbol=? ORDER BY day", arrayOf(symbol)).use { c ->
            List(c.count) { c.moveToNext(); c.getLong(0) to c.getDouble(1) }
        }
    }

    // --- Yahoo's dividend and split tables ------------------------------------------

    /** Stores what one daily answer said about dividends and splits; with [replaceAll] the stock's earlier records go first. */
    suspend fun saveYahooEvents(code: String, dividends: List<YahooDividend>, splits: List<YahooSplit>, replaceAll: Boolean = false) =
        access { db ->
            db.inTransaction {
                if (replaceAll) {
                    delete("yahoo_dividend", "code=?", arrayOf(code))
                    delete("yahoo_split", "code=?", arrayOf(code))
                }
                for (d in dividends) execSQL("INSERT OR REPLACE INTO yahoo_dividend (code, ex_day, amount) VALUES (?,?,?)",
                    arrayOf<Any>(code, d.exDay, d.amount))
                for (s in splits) execSQL("INSERT OR REPLACE INTO yahoo_split (code, day, ratio) VALUES (?,?,?)",
                    arrayOf<Any>(code, s.day, s.ratio))
            }
        }

    suspend fun yahooDividends(): Map<String, List<YahooDividend>> = access { db ->
        db.rawQuery("SELECT code, ex_day, amount FROM yahoo_dividend ORDER BY code, ex_day", null).use { c ->
            val out = LinkedHashMap<String, MutableList<YahooDividend>>()
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList() } += YahooDividend(c.getLong(1), c.getDouble(2))
            out
        }
    }

    suspend fun splits(code: String): List<YahooSplit> = access { db ->
        db.rawQuery("SELECT day, ratio FROM yahoo_split WHERE code=? ORDER BY day", arrayOf(code)).use { c ->
            List(c.count) { c.moveToNext(); YahooSplit(c.getLong(0), c.getDouble(1)) }
        }
    }

    // --- download state ------------------------------------------------------------

    suspend fun syncState(code: String): EgxSyncState? = access { db ->
        db.rawQuery("SELECT full_at, last_day, last_split_day, missing_at FROM sync_state WHERE code=?", arrayOf(code)).use { c ->
            if (!c.moveToFirst()) null
            else EgxSyncState(code, c.getLong(0), c.longOrNull(1), c.longOrNull(2), c.getLong(3))
        }
    }

    suspend fun saveSyncState(s: EgxSyncState) = access { db ->
        db.execSQL("INSERT OR REPLACE INTO sync_state (code, full_at, last_day, last_split_day, missing_at) VALUES (?,?,?,?,?)",
            arrayOf<Any?>(s.code, s.fullAt, s.lastDay, s.lastSplitDay, s.missingAt))
    }

    // --- dividends -----------------------------------------------------------------

    suspend fun dividends(): List<EgxDividend> = access { db ->
        db.rawQuery("SELECT * FROM dividend ORDER BY id", null).use { c -> List(c.count) { c.moveToNext(); c.dividend() } }
    }

    suspend fun dividend(id: Long): EgxDividend? = access { db ->
        db.rawQuery("SELECT * FROM dividend WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.dividend() else null }
    }

    /** Inserts a dividend whose id is 0 and updates the rest; returns the ids in the same order. */
    suspend fun saveDividends(list: List<EgxDividend>): List<Long> = access { db ->
        db.inTransaction {
            list.map { d ->
                val v = ContentValues().apply {
                    put("code", d.code); put("amount", d.amount); put("assembly_day", d.assemblyDay)
                    put("payment_day", d.paymentDay); put("last_cum_day", d.lastCumDay); put("announced_day", d.announcedDay)
                    put("last_cum_estimated", if (d.lastCumEstimated) 1 else 0); put("source", d.source.name)
                    put("by_hand", if (d.byHand) 1 else 0); put("cancelled", if (d.cancelled) 1 else 0)
                    put("yahoo_ex_day", d.yahooExDay); put("yahoo_amount", d.yahooAmount); put("check_state", d.check.name)
                    put("headline", d.headline); put("url", d.url); put("updated_at", d.updatedAt)
                }
                if (d.id == 0L) insertOrThrow("dividend", null, v)
                else {
                    update("dividend", v, "id=?", arrayOf(d.id.toString()))
                    d.id
                }
            }
        }
    }

    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private fun Cursor.dividend(): EgxDividend {
        fun col(name: String) = getColumnIndexOrThrow(name)
        fun long(name: String) = col(name).let { if (isNull(it)) null else getLong(it) }
        fun double(name: String) = col(name).let { if (isNull(it)) null else getDouble(it) }
        return EgxDividend(
            id = getLong(col("id")), code = getString(col("code")), amount = double("amount"),
            assemblyDay = long("assembly_day"), paymentDay = long("payment_day"), lastCumDay = long("last_cum_day"), announcedDay = long("announced_day"),
            lastCumEstimated = getInt(col("last_cum_estimated")) != 0,
            source = DividendSource.valueOf(getString(col("source"))), byHand = getInt(col("by_hand")) != 0,
            cancelled = getInt(col("cancelled")) != 0, yahooExDay = long("yahoo_ex_day"), yahooAmount = double("yahoo_amount"),
            check = runCatching { DividendCheck.valueOf(getString(col("check_state"))) }.getOrDefault(DividendCheck.WAITING),
            headline = getString(col("headline")), url = getString(col("url")), updatedAt = getLong(col("updated_at")),
        )
    }

    companion object {
        const val VERSION = 2
    }
}
