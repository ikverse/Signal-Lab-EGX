package com.ikverse.signallab.engine.egx

import com.ikverse.signallab.engine.Market
import com.ikverse.signallab.engine.Markets

/** A stock held by a paper account: bought at session [k] for [price], with the average true range of the session before. */
class Holding(val code: String, var shares: Double, val k: Int, val price: Double, val atr: Double) {
    /** True once target 1 sold half. */
    var half: Boolean = false
}

/** What a strategy wants after a session's close: stocks to sell, and stocks to buy in order of preference. */
class Decision(val sells: List<String>, val buys: List<String>) {
    val isEmpty: Boolean get() = sells.isEmpty() && buys.isEmpty()

    companion object {
        val NONE = Decision(emptyList(), emptyList())

        /** A list rule's answer: keep what stays on [want], sell the rest, buy what is new (item 6: no sell-and-rebuy). */
        fun toList(held: Map<String, Holding>, want: List<String>): Decision =
            Decision(held.keys.filter { it !in want }, want.filter { it !in held })
    }
}

/**
 * The four levels of a call (docs/egx-plan.md part 5), in average true ranges ("normal days' moves") of the session the
 * call was made on. A null level is not used.
 */
class Levels(
    /** Fill only at or below the last close; the lower edge of the entry range is half a move below it. */
    val entryRange: Boolean = false,
    val stopAtr: Double? = null,
    /** At target 1 the account sells half. */
    val target1Atr: Double? = null,
    /** At target 2 it sells the rest. */
    val target2Atr: Double? = null,
) {
    companion object {
        /** By the rule alone: no levels. The second record. */
        val NONE = Levels()

        /** What the paper account uses, decided by the owner on 2026-11-14: the range, a stop at four, targets at five and ten. */
        val PLAN = Levels(entryRange = true, stopAtr = 4.0, target1Atr = 5.0, target2Atr = 10.0)

        /** Half a move: the entry range's depth below the last close. */
        const val RANGE_DEPTH_ATR = 0.5
    }
}

/** A call's four price levels in pounds, rounded to the exchange's step, from the close and the average true range. */
class CallLevels(val entryLow: Double, val entryHigh: Double, val stop: Double?, val target1: Double?, val target2: Double?) {
    companion object {
        fun of(close: Double, atr: Double, levels: Levels = Levels.PLAN, market: Market = Markets.EGX): CallLevels = CallLevels(
            entryLow = market.round(close - Levels.RANGE_DEPTH_ATR * atr),
            entryHigh = market.round(close),
            stop = levels.stopAtr?.let { market.round(close - it * atr) },
            target1 = levels.target1Atr?.let { market.round(close + it * atr) },
            target2 = levels.target2Atr?.let { market.round(close + it * atr) },
        )
    }
}

/** What a strategy decides with: the board up to the session, and the dividends known by then. */
class Context(val board: Board, val dividends: DividendBook)

/**
 * A rule for the Egyptian app. Unlike a crypto strategy, which flags entries one coin at a time, a rule holds a few stocks
 * in slots and answers each evening with what to sell and buy. It may read only sessions up to [k] and dividends
 * announced by then; a test holds every registered rule to that.
 */
interface SlotRule {
    val id: String

    /** How many stocks it holds at most; the money is split into this many equal slots. */
    val slots: Int

    /** How many of the busiest stocks it chooses among. */
    val universeSize: Int

    fun decide(ctx: Context, k: Int, held: Map<String, Holding>): Decision

    /**
     * The stocks a random draw for this rule may pick at session [k]: the ones the rule chooses among. A monthly list
     * that needs 57 traded sessions of 60 draws from those.
     */
    fun pool(ctx: Context, k: Int): List<String> =
        ctx.board.universe(k, universeSize).filter { ctx.board.stocks.getValue(it).tradedOf(k, 60) >= 57 }
}

/** Why a holding left. */
enum class Exit { RULE, STOP, TARGET1, TARGET2, DELISTED }

/** One fill of a paper account. [shares] is what changed hands; [price] the price before costs. */
class SlotFill(val k: Int, val code: String, val buy: Boolean, val shares: Double, val price: Double, val exit: Exit? = null)

/** The account at one close. */
class SlotDay(val k: Int, val equity: Double, val held: Double)

class SlotRun(val days: List<SlotDay>, val fills: List<SlotFill>, val decisions: List<Decision>, val dividends: Double)

/**
 * The paper account of one rule, session by session, exactly as the research's `run_levels` follows it:
 *
 * 1. At the open, sells decided last evening fill at the open, unless the stock is locked at its limit or did not trade
 *    (the order waits, item 5). A stock that left the data is paid out at its last close.
 * 2. Buys fill at the open in order of preference while a slot is free, each with the account's value at the last close
 *    divided by the slots (or what cash is left). With the entry range, a buy fills at the last close or below, or not at all.
 *    A buy blocked because the stock is locked at its limit or did not trade waits for the next session it trades, at most
 *    [MAX_WAIT] sessions and only until the rule decides again (item 5); the research dropped such a buy, so
 *    [buysWait] = false reproduces it.
 * 3. Idle cash earns a session's interest (item 4); a dividend whose ex-day this is pays its holders after tax.
 * 4. Levels are checked on the session's range: target 1 sells half, target 2 the rest, a close below the stop sells at
 *    the next open.
 * 5. At the close the rule decides; its sells and buys are orders for the next session.
 */
object Slots {
    fun run(
        rule: SlotRule,
        ctx: Context,
        from: Int,
        /** The round trip for a stock at a session. */
        cost: (String, Int) -> Double,
        /** Interest on idle money for each session of the board. */
        cashPerSession: DoubleArray,
        levels: Levels = Levels.NONE,
        market: Market = Markets.EGX,
        start: Double = 1.0,
        /** False reproduces the research, which dropped a buy it could not fill. */
        buysWait: Boolean = true,
        /** Overrides the rule's decision at each session, for random draws that follow its timing. */
        decide: ((Int, Map<String, Holding>) -> Decision)? = null,
    ): SlotRun {
        val board = ctx.board
        val slots = rule.slots
        var cash = start
        var equity = start
        val held = LinkedHashMap<String, Holding>()
        val sells = ArrayList<String>()
        var buys: List<String> = emptyList()
        val last = HashMap<String, Double>()
        val days = ArrayList<SlotDay>()
        val fills = ArrayList<SlotFill>()
        val decisions = ArrayList<Decision>()
        var dividends = 0.0
        val exits = HashMap<String, Exit>()
        val waiting = LinkedHashMap<String, Int>()
        for (k in from until board.size) {
            for (code in ArrayList(sells)) {
                val s = board.stocks.getValue(code)
                val h = held[code]
                if (h != null && canFill(s, k, buy = false, market)) {
                    cash += h.shares * s.open[k] * (1 - cost(code, k) / 2)
                    fills.add(SlotFill(k, code, false, h.shares, s.open[k], exits.remove(code) ?: Exit.RULE))
                    held.remove(code)
                    sells.remove(code)
                } else if (h == null) {
                    sells.remove(code)
                } else if (!s.alive(k)) {
                    val p = last.getValue(code)
                    cash += h.shares * p * (1 - cost(code, k) / 2)
                    fills.add(SlotFill(k, code, false, h.shares, p, Exit.DELISTED))
                    exits.remove(code)
                    held.remove(code)
                    sells.remove(code)
                }
            }
            waiting.entries.removeAll { k - it.value > MAX_WAIT || it.key in held }
            val todays = if (waiting.isEmpty()) buys else buys + waiting.keys.filter { it !in buys }
            for (code in todays) {
                val s = board.stocks.getValue(code)
                if (code in held || held.size >= slots) {
                    waiting.remove(code)
                    continue
                }
                if (!canFill(s, k, buy = true, market)) {
                    if (buysWait) waiting.putIfAbsent(code, k)
                    continue
                }
                waiting.remove(code)
                var price = s.open[k]
                if (levels.entryRange) {
                    val ref = s.close[k - 1]
                    if (ref.isNaN() || s.low[k] > ref) continue // it never came back into the entry range: no fill
                    price = minOf(price, ref)
                }
                val amount = minOf(equity / slots, cash)
                if (amount <= 0) break
                cash -= amount
                val shares = amount * (1 - cost(code, k) / 2) / price
                held[code] = Holding(code, shares, k, price, s.atr[k - 1])
                fills.add(SlotFill(k, code, true, shares, price))
            }
            buys = emptyList()
            cash *= 1 + cashPerSession[k]
            for (code in ArrayList(held.keys)) {
                val h = held.getValue(code)
                val s = board.stocks.getValue(code)
                val d = ctx.dividends.exOn(board, code, k)
                if (d != null && h.k < k && k > 0 && s.alive(k - 1)) {
                    val paid = h.shares * s.close[k - 1] * d.share * (1 - market.dividendTax)
                    cash += paid
                    dividends += paid
                }
                if (!s.traded(k) || h.atr.isNaN() || h.k == k) continue
                val t1 = levels.target1Atr
                if (t1 != null && !h.half && s.high[k] >= h.price + t1 * h.atr) {
                    val p = h.price + t1 * h.atr
                    cash += h.shares / 2 * p * (1 - cost(code, k) / 2)
                    fills.add(SlotFill(k, code, false, h.shares / 2, p, Exit.TARGET1))
                    h.shares /= 2
                    h.half = true
                }
                val t2 = levels.target2Atr
                if (t2 != null && s.high[k] >= h.price + t2 * h.atr) {
                    val p = h.price + t2 * h.atr
                    cash += h.shares * p * (1 - cost(code, k) / 2)
                    fills.add(SlotFill(k, code, false, h.shares, p, Exit.TARGET2))
                    held.remove(code)
                    continue
                }
                val stop = levels.stopAtr
                if (stop != null && s.close[k] < h.price - stop * h.atr && code !in sells) {
                    sells.add(code)
                    exits[code] = Exit.STOP
                }
            }
            var worth = 0.0
            for ((code, h) in held) {
                val s = board.stocks.getValue(code)
                var c = if (s.alive(k)) s.close[k] else Double.NaN
                if (c.isNaN()) {
                    c = last[code] ?: h.price
                    if (code !in sells) sells.add(code)
                }
                last[code] = c
                worth += h.shares * c
            }
            equity = cash + worth
            days.add(SlotDay(k, equity, if (equity != 0.0) worth / equity else 0.0))
            val dec = decide?.invoke(k, held) ?: rule.decide(ctx, k, held)
            decisions.add(dec)
            for (code in dec.sells) if (code !in sells) sells.add(code)
            buys = dec.buys.filter { it !in held }
            if (!dec.isEmpty) waiting.keys.retainAll(buys.toSet()) // the rule decided again: only what it still wants may wait
        }
        return SlotRun(days, fills, decisions, dividends)
    }

    /**
     * Whether a paper order can fill at session [k]: the stock traded, and it was not locked at its limit in the order's
     * direction (one price all session, up at least [Market.lockedMove] for a buy or down that far for a sell).
     */
    fun canFill(s: Stock, k: Int, buy: Boolean, market: Market = Markets.EGX): Boolean {
        if (!s.traded(k)) return false
        if (market.lockedMove > 0 && s.high[k] == s.low[k] && k > 0 && s.alive(k - 1) && s.close[k - 1] != 0.0) {
            val move = s.close[k] / s.close[k - 1] - 1
            if ((buy && move >= market.lockedMove) || (!buy && move <= -market.lockedMove)) return false
        }
        return true
    }

    /** The most sessions a blocked buy waits for its stock to trade. */
    const val MAX_WAIT = 5

    /** Interest a session on idle money at one yearly rate, for a whole board. */
    fun flatCash(board: Board, yearlyRate: Double, market: Market = Markets.EGX): DoubleArray =
        DoubleArray(board.size) { market.sessionRate(yearlyRate) }
}
