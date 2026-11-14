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

@Dao
interface EgxLedgerDao {
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
}

@Database(
    entities = [PaperFillRow::class, PaperDayRow::class, YardstickRow::class, CallRow::class, CopyRow::class, PassRow::class],
    version = 1,
)
abstract class EgxLedgerDatabase : RoomDatabase() {
    abstract fun dao(): EgxLedgerDao

    companion object {
        fun open(context: Context, name: String? = "egx-ledger.db"): EgxLedgerDatabase {
            val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, EgxLedgerDatabase::class.java)
            else Room.databaseBuilder(context, EgxLedgerDatabase::class.java, name)
            return builder.build()
        }
    }
}
