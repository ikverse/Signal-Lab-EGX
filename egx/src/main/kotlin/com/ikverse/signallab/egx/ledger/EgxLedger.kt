package com.ikverse.signallab.egx.ledger

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Which record a row belongs to. The paper account follows the four levels ([LEVELS]); the second record follows each
 * rule alone ([RULE]); both are also kept at double the costs. A random twin's draws are summarised, not stored per fill.
 */
object Records {
    const val LEVELS = "levels"
    const val RULE = "rule"
    const val LEVELS_DOUBLE = "levels_x2"
    const val RULE_DOUBLE = "rule_x2"
    val ALL = listOf(LEVELS, RULE, LEVELS_DOUBLE, RULE_DOUBLE)

    /** The technical picks' own paper account, one per list (strategy = the list's id). */
    const val PICKS = "picks"
}

/**
 * One paper fill, in pounds. A strategy's paper account starts with its fixed share of "money I trade with" on the day it
 * was turned on. Rows are written once and never rewritten: the ledger is append-only, so a later correction by Yahoo
 * cannot change what the ledger already said.
 */
@Entity(tableName = "paper_fill", primaryKeys = ["strategy", "record", "day", "code", "buy", "exit"])
data class PaperFillRow(
    val strategy: String,
    val record: String,
    val day: Long,
    val code: String,
    val buy: Boolean,
    val shares: Double,
    /** The price before costs. */
    val price: Double,
    /** Why it sold ("rule", "stop", "target1", "target2", "delisted"); "" for a buy. */
    val exit: String,
    /** Costs paid on this fill, in pounds. */
    val cost: Double,
)

/** A strategy's paper account at one close, in pounds, with what it held in stocks and the dividends paid in so far. */
@Entity(tableName = "paper_day", primaryKeys = ["strategy", "record", "day"])
data class PaperDayRow(
    val strategy: String,
    val record: String,
    val day: Long,
    val equity: Double,
    val held: Double,
    val dividends: Double,
)

/** The yardsticks for a strategy at one close, over the same sessions as its record: what 1.00 became. */
@Entity(tableName = "yardstick_day", primaryKeys = ["strategy", "day"])
data class YardstickRow(
    val strategy: String,
    val day: Long,
    val cash: Double,
    /** NaN while the EGX30 has no close for the strategy's first session. */
    val egx30: Double,
    val randomLow: Double,
    val randomMiddle: Double,
    val randomHigh: Double,
)

/**
 * A call for the next session: what to do, how much, at what levels and when. Calls of an evening are written together;
 * [placed] is the owner's "placed the order".
 */
@Entity(tableName = "call", primaryKeys = ["day", "strategy", "code", "action"])
data class CallRow(
    /** The session after whose close it was made. */
    val day: Long,
    val strategy: String,
    val code: String,
    /** "buy", "sell", "sell_half" (target 1), "keep" (on a month-end list). */
    val action: String,
    val shares: Long,
    val pounds: Double,
    val close: Double,
    val entryLow: Double?,
    val entryHigh: Double?,
    val stop: Double?,
    val target1: Double?,
    val target2: Double?,
    /** "close", "open", "wait" (a day after a jump of 8%), "limit" (a sell at a target). */
    val timing: String,
    /** A dividend call's last day to hold and the day the cash arrives; null otherwise. */
    val lastCumDay: Long?,
    val paymentDay: Long?,
    /** Why, as a key the screens turn into a sentence ("monthly_list", "dividend_announced", "breakout", "stop", ...). */
    val reason: String,
    val monthEnd: Boolean,
    /** The round trip for this stock (0.5% for the 30 busiest, 0.8% for the rest). */
    val roundTrip: Double = 0.0,
    /** A normal day's move: the 14-session average true range the levels are measured in. */
    val atr: Double = 0.0,
    val placed: Boolean = false,
)

/** "I took it": what the owner really did on Thndr, at the real price. */
@Entity(tableName = "copy")
data class CopyRow(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val strategy: String,
    val code: String,
    val buy: Boolean,
    val day: Long,
    val shares: Long,
    val price: Double,
    val note: String = "",
)

/** One evening pass: when it ran, for which session, and what happened. */
@Entity(tableName = "pass")
data class PassRow(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val session: Long?,
    val ok: Boolean,
    val message: String,
)

/** One answer from the analyst, kept so the owner can read it again. */
@Entity(tableName = "analyst_answer")
data class AnalystAnswerRow(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val question: String,
    val answer: String,
    /** How the PC host ended the run ("done", "cut short", ...). */
    val ending: String,
)

/**
 * A technical pick, as its card showed it on the evening it was made (docs/egx-plan.md, technical picks). Written once.
 * [shown] says whether its list put it on Today; a list that is off or switched off is still recorded.
 */
@Entity(tableName = "pick", primaryKeys = ["day", "list", "code"])
data class PickRow(
    /** The session after whose close it was made. */
    val day: Long,
    /** The list's id ("egx_short_term", "egx_mid_term"). */
    val list: String,
    /** The setup version of that month. */
    val version: String,
    val code: String,
    /** Its place among the evening's picks of the list, 1 first. */
    val rank: Int,
    val close: Double,
    val atr: Double,
    val entryLow: Double,
    val entryHigh: Double,
    val stop: Double,
    val target1: Double?,
    val target2: Double?,
    /** Sessions it is held at most. */
    val limit: Int,
    /** It is sold on a close under this average; null for none. */
    val exitAverage: Int?,
    /** The breakout level it closed above (short term); null otherwise. */
    val breakout: Double?,
    val near: Double,
    val volumeRatio: Double,
    /** The last 10 sessions' range over the close (mid term); 0 otherwise. */
    val tightness: Double,
    val shown: Boolean,
    /** A headline on the company in the 10 days before. */
    val hadNews: Boolean = false,
    /** What those headlines were about: "results", "assembly", "capital_increase", "dividend", comma-separated. */
    val flags: String = "",
    /** The company's last twelve months ended in a loss; null when unknown. */
    val lossMaking: Boolean? = null,
)

/**
 * What happened to a pick: "bought", "target1", "target2", "stop", "time", "rule", "delisted", "expired" on the
 * session it happened, and "sell_stop", "sell_time", "sell_rule" on the evening a sell for the next open was decided.
 */
@Entity(tableName = "pick_event", primaryKeys = ["day", "list", "code", "pickDay", "kind"])
data class PickEventRow(val day: Long, val list: String, val code: String, val pickDay: Long, val kind: String, val price: Double)

/** The monthly learning step's choice: the version a list uses for a month, and how it and the registered one had done. */
@Entity(tableName = "pick_choice", primaryKeys = ["month", "list"])
data class PickChoiceRow(val month: String, val list: String, val version: String, val growth: Double, val registeredGrowth: Double)

/** A list's record of finished trades, the latest only: in the backtest before the list went live, and live. */
@Entity(tableName = "pick_record", primaryKeys = ["list", "scope"])
data class PickRecordRow(
    val list: String,
    /** "backtest" or "live". */
    val scope: String,
    val day: Long,
    val trades: Int,
    val won: Double,
    val target1: Double,
    val target2: Double,
    val stopped: Double,
    val average: Double,
    val days: Int,
)

/** The market's state at a close. */
@Entity(tableName = "market_day", primaryKeys = ["day"])
data class MarketDayRow(val day: Long, val egx30: Double?, val above50: Boolean?, val above200: Boolean?, val uptrend: Int, val counted: Int)

/** A company's facts as last read (a cache, replaced when read again). */
@Entity(tableName = "stock_facts", primaryKeys = ["code"])
data class FactsRow(
    val code: String,
    val fetched: Long,
    val peRatio: Double?,
    val netIncome: Double?,
    val eps: Double?,
    val totalDebt: Double?,
    val debtToEquity: Double?,
)

/** A company headline (a cache, kept once per item). */
@Entity(tableName = "headline", primaryKeys = ["url"])
data class HeadlineRow(val url: String, val code: String, val headline: String, val published: Long?, val flags: String, val fetched: Long)

@Dao
interface EgxLedgerDao {
    @Insert
    suspend fun addAnswer(row: AnalystAnswerRow): Long

    @Query("SELECT * FROM analyst_answer ORDER BY at DESC LIMIT 50")
    fun answers(): Flow<List<AnalystAnswerRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFills(rows: List<PaperFillRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addDays(rows: List<PaperDayRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putYardsticks(rows: List<YardstickRow>)

    @Query("SELECT MAX(day) FROM paper_day WHERE strategy = :strategy AND record = :record")
    suspend fun lastDay(strategy: String, record: String): Long?

    @Query("SELECT * FROM paper_fill WHERE record = :record ORDER BY day, strategy, code")
    fun fills(record: String): Flow<List<PaperFillRow>>

    @Query("SELECT * FROM paper_fill WHERE record = :record AND day >= :from ORDER BY day")
    suspend fun fillsSince(record: String, from: Long): List<PaperFillRow>

    @Query("SELECT * FROM paper_day WHERE strategy = :strategy AND record = :record ORDER BY day")
    suspend fun days(strategy: String, record: String): List<PaperDayRow>

    @Query("SELECT * FROM paper_day WHERE record = :record ORDER BY day")
    fun allDays(record: String): Flow<List<PaperDayRow>>

    @Query("SELECT * FROM yardstick_day ORDER BY day")
    fun yardsticks(): Flow<List<YardstickRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCalls(rows: List<CallRow>)

    @Query("DELETE FROM call WHERE day = :day AND placed = 0")
    suspend fun clearUnplacedCalls(day: Long)

    @Query("SELECT * FROM call WHERE day = (SELECT MAX(day) FROM call) ORDER BY action DESC, strategy, code")
    fun latestCalls(): Flow<List<CallRow>>

    @Query("SELECT * FROM call WHERE day >= :from ORDER BY day")
    suspend fun callsSince(from: Long): List<CallRow>

    /** Every call ever made on one stock, newest first: the stock page's history. */
    @Query("SELECT * FROM call WHERE code = :code ORDER BY day DESC")
    fun callsFor(code: String): Flow<List<CallRow>>

    @Query("UPDATE call SET placed = :placed WHERE day = :day AND strategy = :strategy AND code = :code AND action = :action")
    suspend fun setPlaced(day: Long, strategy: String, code: String, action: String, placed: Boolean)

    @Insert
    suspend fun addCopy(row: CopyRow): Long

    @Query("SELECT * FROM copy ORDER BY day DESC, id DESC")
    fun copies(): Flow<List<CopyRow>>

    @Insert
    suspend fun addPass(row: PassRow): Long

    @Query("SELECT * FROM pass ORDER BY at DESC LIMIT 1")
    fun lastPass(): Flow<PassRow?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPicks(rows: List<PickRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPickEvents(rows: List<PickEventRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPickChoices(rows: List<PickChoiceRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPickRecords(rows: List<PickRecordRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMarketDays(rows: List<MarketDayRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFacts(row: FactsRow)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addHeadlines(rows: List<HeadlineRow>)

    @Query("SELECT * FROM pick ORDER BY day DESC, list, rank")
    fun picks(): Flow<List<PickRow>>

    @Query("SELECT * FROM pick WHERE day >= :from ORDER BY day DESC, list, rank")
    suspend fun picksSince(from: Long): List<PickRow>

    @Query("SELECT * FROM pick_event ORDER BY day")
    fun pickEvents(): Flow<List<PickEventRow>>

    @Query("SELECT * FROM pick_choice ORDER BY month")
    fun pickChoices(): Flow<List<PickChoiceRow>>

    @Query("SELECT * FROM pick_record")
    fun pickRecords(): Flow<List<PickRecordRow>>

    @Query("SELECT * FROM market_day ORDER BY day DESC LIMIT 1")
    fun lastMarket(): Flow<MarketDayRow?>

    @Query("SELECT * FROM stock_facts WHERE code = :code")
    suspend fun facts(code: String): FactsRow?

    @Query("SELECT * FROM stock_facts")
    fun allFacts(): Flow<List<FactsRow>>

    @Query("SELECT * FROM headline WHERE code = :code ORDER BY published DESC LIMIT :limit")
    suspend fun headlines(code: String, limit: Int = 20): List<HeadlineRow>

    @Query("SELECT * FROM headline ORDER BY published DESC LIMIT 400")
    fun recentHeadlines(): Flow<List<HeadlineRow>>
}

@Database(
    entities = [
        PaperFillRow::class, PaperDayRow::class, YardstickRow::class, CallRow::class, CopyRow::class, PassRow::class, AnalystAnswerRow::class,
        PickRow::class, PickEventRow::class, PickChoiceRow::class, PickRecordRow::class, MarketDayRow::class, FactsRow::class, HeadlineRow::class,
    ],
    version = 3,
)
abstract class EgxLedgerDatabase : RoomDatabase() {
    abstract fun dao(): EgxLedgerDao

    companion object {
        /** Version 2 adds the analyst's answers; every ledger row stays as it was. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `analyst_answer` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `question` TEXT NOT NULL, `answer` TEXT NOT NULL, `ending` TEXT NOT NULL)")
            }
        }

        /** Version 3 adds the technical picks' tables and the facts and headlines read for them; every row stays as it was. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                for (sql in V3_TABLES) db.execSQL(sql)
            }
        }

        /** Room's own create statements for the version-3 tables (schemas/.../3.json). */
        val V3_TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS `pick` (`day` INTEGER NOT NULL, `list` TEXT NOT NULL, `version` TEXT NOT NULL, `code` TEXT NOT NULL, `rank` INTEGER NOT NULL, `close` REAL NOT NULL, `atr` REAL NOT NULL, `entryLow` REAL NOT NULL, `entryHigh` REAL NOT NULL, `stop` REAL NOT NULL, `target1` REAL, `target2` REAL, `limit` INTEGER NOT NULL, `exitAverage` INTEGER, `breakout` REAL, `near` REAL NOT NULL, `volumeRatio` REAL NOT NULL, `tightness` REAL NOT NULL, `shown` INTEGER NOT NULL, `hadNews` INTEGER NOT NULL, `flags` TEXT NOT NULL, `lossMaking` INTEGER, PRIMARY KEY(`day`, `list`, `code`))",
            "CREATE TABLE IF NOT EXISTS `pick_event` (`day` INTEGER NOT NULL, `list` TEXT NOT NULL, `code` TEXT NOT NULL, `pickDay` INTEGER NOT NULL, `kind` TEXT NOT NULL, `price` REAL NOT NULL, PRIMARY KEY(`day`, `list`, `code`, `pickDay`, `kind`))",
            "CREATE TABLE IF NOT EXISTS `pick_choice` (`month` TEXT NOT NULL, `list` TEXT NOT NULL, `version` TEXT NOT NULL, `growth` REAL NOT NULL, `registeredGrowth` REAL NOT NULL, PRIMARY KEY(`month`, `list`))",
            "CREATE TABLE IF NOT EXISTS `pick_record` (`list` TEXT NOT NULL, `scope` TEXT NOT NULL, `day` INTEGER NOT NULL, `trades` INTEGER NOT NULL, `won` REAL NOT NULL, `target1` REAL NOT NULL, `target2` REAL NOT NULL, `stopped` REAL NOT NULL, `average` REAL NOT NULL, `days` INTEGER NOT NULL, PRIMARY KEY(`list`, `scope`))",
            "CREATE TABLE IF NOT EXISTS `market_day` (`day` INTEGER NOT NULL, `egx30` REAL, `above50` INTEGER, `above200` INTEGER, `uptrend` INTEGER NOT NULL, `counted` INTEGER NOT NULL, PRIMARY KEY(`day`))",
            "CREATE TABLE IF NOT EXISTS `stock_facts` (`code` TEXT NOT NULL, `fetched` INTEGER NOT NULL, `peRatio` REAL, `netIncome` REAL, `eps` REAL, `totalDebt` REAL, `debtToEquity` REAL, PRIMARY KEY(`code`))",
            "CREATE TABLE IF NOT EXISTS `headline` (`url` TEXT NOT NULL, `code` TEXT NOT NULL, `headline` TEXT NOT NULL, `published` INTEGER, `flags` TEXT NOT NULL, `fetched` INTEGER NOT NULL, PRIMARY KEY(`url`))",
        )

        fun open(context: Context, name: String? = "egx-ledger.db"): EgxLedgerDatabase {
            val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, EgxLedgerDatabase::class.java)
            else Room.databaseBuilder(context, EgxLedgerDatabase::class.java, name)
            return builder.addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        }
    }
}
