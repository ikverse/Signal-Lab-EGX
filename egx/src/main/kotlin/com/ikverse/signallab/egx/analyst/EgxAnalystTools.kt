package com.ikverse.signallab.egx.analyst

import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.data.DailyRow
import com.ikverse.signallab.egx.data.DividendBook
import com.ikverse.signallab.egx.data.EgxDividend
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.CopyRow
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.FactsRow
import com.ikverse.signallab.egx.ledger.HeadlineRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.PickEventRow
import com.ikverse.signallab.egx.ledger.PickRecordRow
import com.ikverse.signallab.egx.ledger.PickRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.egx.view.Evidence
import com.ikverse.signallab.egx.view.trades
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.engine.egx.EgxStrategies
import org.json.JSONArray
import org.json.JSONObject

/** What the analyst's tools read: the phone's own ledger and prices. Nothing here writes. */
interface EgxAnalystData {
    suspend fun latestCalls(): List<CallRow>
    suspend fun callsSince(day: Long): List<CallRow>
    suspend fun days(record: String): List<PaperDayRow>
    suspend fun yardsticks(): List<YardstickRow>
    suspend fun fills(): List<PaperFillRow>
    suspend fun copies(): List<CopyRow>
    suspend fun daily(code: String): List<DailyRow>
    suspend fun dividends(): List<EgxDividend>
    suspend fun egx30(): List<Pair<Long, Double>>
    fun prefs(): EgxPrefs
    fun name(code: String): String?
    fun today(): Long

    /** The technical picks since [day], newest first, with their events and the lists' records. */
    suspend fun picksSince(day: Long): List<PickRow> = emptyList()
    suspend fun pickEvents(): List<PickEventRow> = emptyList()
    suspend fun pickRecords(): List<PickRecordRow> = emptyList()

    /** A company's latest headlines kept by the app, and its facts. */
    suspend fun headlines(code: String): List<HeadlineRow> = emptyList()
    suspend fun facts(code: String): FactsRow? = null
}

/** One tool the phone offers Claude Code through the PC host. */
class EgxTool(val name: String, val description: String, val schema: JSONObject)

/** The tools' answer: text for the model, and whether it is an error. */
class ToolOutput(val text: String, val isError: Boolean = false)

/**
 * The analyst's seven read-only tools, run on the phone on its own data. Each answers in JSON with dates as yyyy-mm-dd and
 * money in Egyptian pounds.
 */
class EgxAnalystTools(private val data: EgxAnalystData) {
    val all: List<EgxTool> = listOf(
        EgxTool("ledger", "The paper account and every strategy's live record against its three yardsticks (cash, the EGX30, eight random stocks picked the same days), with costs, dividends and the owner's own copies.", obj()),
        EgxTool("calls", "The calls of the latest evening pass (what to do at the next session), or every call since days_back calendar days ago.", obj("days_back" to prop("integer", "Optional: calendar days to look back; 0 or absent for the latest evening only."))),
        EgxTool("stock", "One stock by its EGX ticker (such as COMI): its name, its last closes, its dividends and every paper trade the strategies made on it.", obj("code" to prop("string", "The EGX ticker, such as COMI."), "sessions" to prop("integer", "Optional: how many recent closes, 1 to 500; 60 if absent."), required = listOf("code"))),
        EgxTool("dividends", "Upcoming cash dividends with their last day to hold and cash date, and those announced with no date yet.", obj()),
        EgxTool("research", "What the research behind each strategy found (2015-2022 and 2023-2026, what 1.00 became, costs included), the yardsticks over the same years, and how many versions were tried in all.", obj()),
        EgxTool("picks", "The technical picks (chart setups on paper: short term with entry range, two targets and a stop; mid term with entry range and stop, no targets) of the latest evening or of the last days_back calendar days, each with its levels, why it was picked and how it stands, and each list's record of finished trades in the backtest and live.",
            obj("days_back" to prop("integer", "Optional: calendar days to look back; 0 or absent for the latest evening only."))),
        EgxTool("headlines", "One stock's latest company headlines from Arab Finance as the app last read them (Arabic), with what each is about, and the company's facts (price to earnings, net income over the last twelve months, debt to equity).",
            obj("code" to prop("string", "The EGX ticker, such as COMI."), required = listOf("code"))),
    )

    suspend fun run(name: String, input: JSONObject): ToolOutput = try {
        when (name) {
            "ledger" -> ToolOutput(ledger().toString())
            "calls" -> ToolOutput(calls(input.optInt("days_back", 0)).toString())
            "stock" -> stock(input.optString("code").trim().uppercase(), input.optInt("sessions", 60).coerceIn(1, 500))
            "dividends" -> ToolOutput(dividends().toString())
            "research" -> ToolOutput(research().toString())
            "picks" -> ToolOutput(picks(input.optInt("days_back", 0)).toString())
            "headlines" -> headlines(input.optString("code").trim().uppercase())
            else -> ToolOutput("There is no tool called $name.", isError = true)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolOutput("The tool failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    private suspend fun ledger(): JSONObject {
        val p = data.prefs()
        val days = data.days(Records.LEVELS)
        val rule = data.days(Records.RULE)
        val double = data.days(Records.LEVELS_DOUBLE)
        val yard = data.yardsticks()
        val fills = data.fills()
        fun last(l: List<PaperDayRow>, s: String) = l.filter { it.strategy == s }.maxByOrNull { it.day }
        val strategies = JSONArray()
        for (s in EgxStrategies.all) {
            val mine = days.filter { it.strategy == s.id }.sortedBy { it.day }
            val y = yard.filter { it.strategy == s.id }.maxByOrNull { it.day }
            strategies.put(JSONObject()
                .put("id", s.id).put("name", s.name).put("summary", s.summary)
                .put("role", when { s.silent -> "control, silent"; s.id in p.on -> "on: in the paper account, calls on Today"; s.launchTier.makesCalls -> "off by the owner"; else -> "watch: recorded, no calls" })
                .putOpt("first_day", mine.firstOrNull()?.day?.let(::iso)).putOpt("last_day", mine.lastOrNull()?.day?.let(::iso))
                .putOpt("equity_egp", mine.lastOrNull()?.equity)
                .putOpt("growth", if (mine.isNotEmpty()) mine.last().equity / mine.first().equity - 1 else null)
                .putOpt("by_rule_alone_egp", last(rule, s.id)?.equity)
                .putOpt("at_double_costs_egp", last(double, s.id)?.equity)
                .putOpt("cash_growth", y?.cash?.minus(1))
                .putOpt("egx30_growth", y?.egx30?.takeIf { !it.isNaN() }?.minus(1))
                .putOpt("random_growth_low_mid_high", y?.let { JSONArray().put(nan(it.randomLow - 1)).put(nan(it.randomMiddle - 1)).put(nan(it.randomHigh - 1)) })
                .put("buys", fills.count { it.strategy == s.id && it.buy })
                .put("costs_paid_egp", fills.filter { it.strategy == s.id }.sumOf { it.cost }))
        }
        val copies = data.copies()
        return JSONObject()
            .put("currency", "EGP").put("money_i_trade_with", p.money).put("cash_rate", p.cashRate)
            .putOpt("ledger_start", p.liveStart?.let(::iso))
            .put("strategies", strategies)
            .put("my_copies", JSONArray().apply { for (c in copies.take(50)) put(JSONObject().put("day", iso(c.day)).put("code", c.code).put("strategy", c.strategy).put("buy", c.buy).put("shares", c.shares).put("price", c.price)) })
            .put("note", "Growth is a share: 0.12 is +12%. Costs are 0.5% a round trip for the 30 busiest stocks and 0.8% for the rest.")
    }

    private suspend fun calls(back: Int): JSONArray {
        val list = if (back <= 0) data.latestCalls() else data.callsSince(data.today() - back * EgxTime.DAY_MS)
        return JSONArray().apply {
            for (c in list) put(JSONObject()
                .put("made_after_session", iso(c.day)).put("strategy", c.strategy).put("code", c.code).putOpt("name", data.name(c.code))
                .put("action", c.action).put("shares", c.shares).put("pounds", c.pounds).put("last_close", c.close)
                .putOpt("entry_low", c.entryLow).putOpt("entry_high", c.entryHigh).putOpt("stop", c.stop).putOpt("target1", c.target1).putOpt("target2", c.target2)
                .put("timing", c.timing).putOpt("last_day_to_hold", c.lastCumDay?.let(::iso)).putOpt("cash_date", c.paymentDay?.let(::iso))
                .put("reason", c.reason).put("month_end", c.monthEnd).put("placed_by_owner", c.placed))
        }
    }

    private suspend fun stock(code: String, sessions: Int): ToolOutput {
        if (code.isEmpty()) return ToolOutput("Give the stock's ticker as code.", isError = true)
        val rows = data.daily(code)
        if (rows.isEmpty() && data.name(code) == null) return ToolOutput("No stock $code is known to the app.", isError = true)
        val tr = Tr.ENGLISH
        val o = JSONObject().put("code", code).putOpt("name", data.name(code))
            .put("closes", JSONArray().apply { for (r in rows.takeLast(sessions)) put(JSONArray().put(iso(r.day)).put(r.close)) })
            .put("dividends", JSONArray().apply { for (d in data.dividends().filter { it.code == code && !it.cancelled }) put(dividend(d)) })
            .put("paper_trades", JSONArray().apply {
                for (t in trades(code, data.fills(), tr)) put(JSONObject().put("strategy", t.strategy).put("opened", iso(t.opened)).putOpt("closed", t.closed?.let(::iso)).putOpt("result_after_costs", t.result))
            })
        return ToolOutput(o.toString())
    }

    private suspend fun dividends(): JSONObject {
        val all = data.dividends()
        val today = data.today()
        return JSONObject()
            .put("upcoming", JSONArray().apply { for (d in DividendBook.upcoming(all, today)) put(dividend(d)) })
            .put("announced_no_date", JSONArray().apply { for (d in DividendBook.undated(all, today)) put(dividend(d)) })
    }

    private fun dividend(d: EgxDividend) = JSONObject().put("code", d.code).putOpt("name", data.name(d.code)).putOpt("egp_a_share", d.amount)
        .putOpt("last_day_to_hold", d.lastCumDay?.let(::iso)).putOpt("cash_date", d.paymentDay?.let(::iso)).putOpt("assembly", d.assemblyDay?.let(::iso))
        .put("last_day_estimated", d.lastCumEstimated).put("checked_on_yahoo", d.check.name.lowercase()).put("by_hand", d.byHand)

    private suspend fun picks(back: Int): JSONObject {
        val all = data.picksSince(if (back <= 0) 0 else data.today() - back * EgxTime.DAY_MS)
        val latest = all.maxOfOrNull { it.day }
        val chosen = if (back <= 0) all.filter { it.day == latest } else all
        val events = data.pickEvents()
        return JSONObject()
            .put("picks", JSONArray().apply {
                for (p in chosen) put(JSONObject()
                    .put("made_after_session", iso(p.day)).put("list", p.list).put("version", p.version).put("code", p.code).putOpt("name", data.name(p.code))
                    .put("rank", p.rank).put("shown_on_today", p.shown).put("last_close", p.close)
                    .put("entry_low", p.entryLow).put("entry_high", p.entryHigh).put("stop", p.stop).putOpt("target1", p.target1).putOpt("target2", p.target2)
                    .put("max_sessions", p.limit).putOpt("exit_under_average", p.exitAverage).putOpt("breakout_level", p.breakout)
                    .put("close_over_year_high", p.near).put("volume_over_average", p.volumeRatio).put("ten_day_range", p.tightness)
                    .put("had_news_10_days", p.hadNews).put("news_flags", p.flags).putOpt("loss_making", p.lossMaking)
                    .put("events", JSONArray().apply {
                        for (e in events.filter { it.list == p.list && it.code == p.code && it.pickDay == p.day }) put(JSONObject().put("day", iso(e.day)).put("kind", e.kind).put("price", e.price))
                    }))
            })
            .put("records", JSONArray().apply {
                for (r in data.pickRecords()) put(JSONObject().put("list", r.list).put("scope", r.scope).put("trades", r.trades).put("won", r.won)
                    .put("reached_target1", r.target1).put("reached_target2", r.target2).put("stopped", r.stopped).put("average_after_costs", r.average).put("median_sessions", r.days))
            })
            .put("note", "Paper only. Events: bought, target1 (half sold), target2, stop, time, rule (close under the exit average), expired (never bought), and sell_* decided at a close for the next open. Shares are fractions: 0.51 is 51%.")
    }

    private suspend fun headlines(code: String): ToolOutput {
        if (code.isEmpty()) return ToolOutput("Give the stock's ticker as code.", isError = true)
        val f = data.facts(code)
        val o = JSONObject().put("code", code).putOpt("name", data.name(code))
            .put("headlines", JSONArray().apply {
                for (h in data.headlines(code)) put(JSONObject().put("headline", h.headline).putOpt("published", h.published?.let(::iso)).put("about", h.flags).put("url", h.url))
            })
            .putOpt("facts", f?.let {
                JSONObject().put("read_on", iso(it.fetched)).putOpt("price_to_earnings", it.peRatio).putOpt("net_income_12m_egp", it.netIncome)
                    .putOpt("eps", it.eps).putOpt("total_debt_egp", it.totalDebt).putOpt("debt_to_equity", it.debtToEquity)
            })
        return ToolOutput(o.toString())
    }

    private suspend fun research(): JSONObject = JSONObject()
        .put("strategies", JSONArray().apply {
            for (s in EgxStrategies.all) {
                val r = Evidence.BY_ID[s.id] ?: continue
                put(JSONObject().put("id", s.id).put("name", s.name).put("summary", s.summary)
                    .put("became_2015_2022", r.old).put("became_2023_2026", r.recent)
                    .putOpt("with_targets_2015_2022", r.levelsOld).putOpt("with_targets_2023_2026", r.levelsRecent).putOpt("worst_fall", r.worstFall))
            }
        })
        .put("cash_became", JSONObject().put("2015_2022", Evidence.CASH_OLD).put("2023_2026", Evidence.CASH_RECENT))
        .put("busiest_30_became", JSONObject().put("2015_2022", Evidence.MARKET_OLD).put("2023_2026", Evidence.MARKET_RECENT))
        .put("egx30_became_2023_2026", Evidence.EGX30_RECENT)
        .put("versions_tried", EgxStrategies.triedCount)
        .put("egx30_last", data.egx30().lastOrNull()?.let { JSONArray().put(iso(it.first)).put(it.second) })
        .put("note", "191 stocks, 2010 to 2026, costs included; each year used only settings that had worked in earlier years. Delisted stocks are missing from Yahoo's data.")

    companion object {
        fun iso(day: Long): String = EgxTime.date(day).toString()
        private fun nan(x: Double): Any = if (x.isNaN()) JSONObject.NULL else x

        private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

        private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
            JSONObject().put("type", "object").put("properties", JSONObject().apply { for ((k, v) in props) put(k, v) }).put("required", JSONArray(required))
    }
}
