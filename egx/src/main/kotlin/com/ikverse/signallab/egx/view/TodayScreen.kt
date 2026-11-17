package com.ikverse.signallab.egx.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.PaperDayRow
import com.ikverse.signallab.egx.ledger.PaperFillRow
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.ledger.YardstickRow
import com.ikverse.signallab.egx.pass.NotificationTexts
import com.ikverse.signallab.engine.egx.EgxStrategies
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One call card, ready to draw. */
data class CardView(val call: CallRow, val action: String, val buy: Boolean, val name: String, val line: String, val chips: List<String>, val placed: Boolean)

/** Today, ready to draw (item 29). */
data class TodayView(
    val dateLine: String,
    val market: String?,
    val sentence: String,
    val cards: List<CardView>,
    val account: String,
    /** Your paper account, the EGX30 and cash since the ledger started, as growth shares; null where unknown. */
    val you: Double?,
    val index: Double?,
    val cash: Double?,
    val barsText: Triple<String, String, String>,
    val ordersUsed: Int,
    val ordersLeft: String,
    /** True on the evening a month ended: Today links to the month-end list. */
    val monthEnd: Boolean = false,
    /** Session by session since the ledger started, as growth shares: the paper account, the EGX30 and cash. */
    val youCurve: List<Double> = emptyList(),
    val indexCurve: List<Double> = emptyList(),
    val cashCurve: List<Double> = emptyList(),
    /** The EGX30's last closes, for the market chip's little line. */
    val marketCurve: List<Double> = emptyList(),
)

/** Thndr's Trader plan waives commission on this many orders a month. */
const val FREE_ORDERS = 50

/** Builds Today from the ledger, with no Android in it, so a test can read every text. */
fun todayView(
    calls: List<CallRow>,
    days: List<PaperDayRow>,
    yardsticks: List<YardstickRow>,
    fills: List<PaperFillRow>,
    prefs: EgxPrefs,
    egx30: List<Pair<Long, Double>>,
    tr: Tr,
    names: (String) -> Pair<String, String>,
    today: LocalDate,
): TodayView {
    val session = calls.maxOfOrNull { it.day }
    val next = session?.let { com.ikverse.signallab.engine.egx.Sessions.nextSession(EgxTime.date(it), prefs.holidays) }         // With no calls yet, the plan is for the next session day: never a Friday, a Saturday or a known holiday.
        ?: com.ikverse.signallab.engine.egx.Sessions.nextSession(today.minusDays(1), prefs.holidays)
    val locale = if (tr.isArabic) Locale.forLanguageTag("ar-EG") else Locale.ENGLISH
    val dateLine = tr.t("Plan for %1\$s", tr.digits("${next.dayOfWeek.getDisplayName(TextStyle.FULL, locale)} ${next.dayOfMonth} ${next.month.getDisplayName(TextStyle.FULL, locale)}"))
    val market = egx30.takeLast(2).takeIf { it.size == 2 }?.let { (a, b) ->
        val level = tr.count(Math.round(b.second))
        when {
            b.second > a.second * 1.002 -> tr.t("Market up · %1\$s", level)
            b.second < a.second * 0.998 -> tr.t("Market down · %1\$s", level)
            else -> tr.t("Market flat · %1\$s", level)
        }
    }
    val shown = calls.filter { it.action != "keep" }
    val cards = shown.sortedBy { if (it.action == "buy") 0 else 1 }.map { c ->
        val (name, ticker) = names(c.code)
        val strategy = EgxStrategies.of(c.strategy)?.name?.let { tr.t(it) } ?: c.strategy
        val why = when (c.reason) {
            "dividend_announced", "dividend_last_day" -> c.lastCumDay?.let { tr.t("Before the dividend · until %1\$s", dayMonth(EgxTime.date(it), tr, locale)) }
            "monthly_list" -> if (c.action == "buy") tr.t("Joined the list") else tr.t("Left the list")
            "stop" -> tr.t("stop loss hit")
            else -> null
        }
        val timing = when (c.timing) {
            "close" -> tr.t("Near the close")
            "open" -> tr.t("At the open")
            "wait" -> tr.t("Wait a day")
            else -> null
        }
        CardView(
            c, if (c.action == "buy") tr.t("Buy") else tr.t("Sell"), c.action == "buy", name,
            listOfNotNull(ticker, if (why != null && c.reason.startsWith("dividend")) why else listOfNotNull(strategy, why).joinToString(" · ")).joinToString(" · "),
            listOfNotNull(tr.t("%1\$d shares", c.shares), tr.t("EGP %1\$s", tr.pounds(c.pounds)), timing), c.placed,
        )
    }
    val on = prefs.on
    val latest = days.filter { it.record == Records.LEVELS && it.strategy in on }.groupBy { it.strategy }.mapValues { it.value.maxBy { d -> d.day } }
    val account = if (latest.isEmpty()) prefs.money else latest.values.sumOf { it.equity }
    val start = prefs.money
    val y = yardsticks.filter { it.strategy in on }.maxByOrNull { it.day }
    val you = if (latest.isEmpty()) null else account / start - 1
    val index = y?.egx30?.takeIf { !it.isNaN() }?.minus(1)
    val cash = y?.cash?.minus(1)
    fun signed(x: Double?) = x?.let { (if (it >= 0) "+" else "") + tr.percent(it, 0) } ?: "–"
    val month = today.withDayOfMonth(1)
    val used = fills.count { it.record == Records.LEVELS && it.strategy in on && !EgxTime.date(it.day).isBefore(month) }
    // The curves: the account summed over the strategies that are on, and the yardsticks, on the same sessions.
    val byDay = days.filter { it.record == Records.LEVELS && it.strategy in on }.groupBy { it.day }.toSortedMap()
    val yardByDay = yardsticks.filter { it.strategy in on }.groupBy { it.day }
    val sessions = byDay.keys.toList()
    val youCurve = sessions.map { d -> byDay.getValue(d).sumOf { it.equity } / start - 1 }
    val indexCurve = sessions.map { d -> yardByDay[d]?.firstOrNull()?.egx30?.takeIf { !it.isNaN() }?.minus(1) ?: Double.NaN }
    val cashCurve = sessions.map { d -> yardByDay[d]?.firstOrNull()?.cash?.minus(1) ?: Double.NaN }
    return TodayView(
        dateLine, market, NotificationTexts.sentence(calls, tr), cards, tr.t("EGP %1\$s", tr.pounds(account)), you, index, cash,
        Triple(tr.t("You %1\$s", signed(you)), tr.t("EGX30 %1\$s", signed(index)), tr.t("Cash %1\$s", signed(cash))),
        used, tr.t("%1\$d left this month", maxOf(0, FREE_ORDERS - used)), calls.any { it.monthEnd },
        youCurve, indexCurve, cashCurve, egx30.takeLast(20).map { it.second },
    )
}

private fun dayMonth(d: LocalDate, tr: Tr, locale: Locale): String = tr.digits("${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, locale)}")

/** The month-end link's text and action, handed down to the head of Today. */
val LocalMonthEndLink = androidx.compose.runtime.staticCompositionLocalOf<Pair<String, () -> Unit>> { "" to {} }

/** The dividend calendar's link, under Today's sentence on every evening. */
val LocalDividendsLink = androidx.compose.runtime.staticCompositionLocalOf<Pair<String, () -> Unit>> { "" to {} }

/** How the Fold is held: folded (one narrow pane), unfolded (two panes side by side), half-folded (above and below the crease). */
enum class Posture { FOLDED, UNFOLDED, TABLETOP }

@Composable
fun TodayScreen(v: TodayView, posture: Posture, tr: Tr, onPlaced: (CardView) -> Unit, onOpen: (CardView) -> Unit, bottom: PaddingValues, onMonthEnd: () -> Unit = {}, onDividends: () -> Unit = {},
    picks: PicksView? = null, onPick: (PickCardView) -> Unit = {}) {
    val b = LocalBoard.current
    androidx.compose.runtime.CompositionLocalProvider(LocalMonthEndLink provides (tr.t("Month-end list") + " ›" to onMonthEnd), LocalDividendsLink provides (tr.t("Dividend calendar ›") to onDividends)) {
    when (posture) {
        Posture.FOLDED -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottom.calculateBottomPadding() + 28.dp)) {
            Head(v, Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
            Spacer(Modifier.height(12.dp))
            if (v.cards.isEmpty()) Empty(tr, Modifier.padding(horizontal = 20.dp)) else {
                val pager = rememberPagerState { v.cards.size }
                HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 20.dp), pageSpacing = 12.dp) { i ->
                    BigCard(v.cards[i], tr, onPlaced, onOpen, i)
                }
                if (v.cards.size > 1) Dots(pager, v.cards.size)
            }
            Tiles(v, tr, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
            Links(v, Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
            if (picks != null) PicksSection(picks, tr, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), onPick)
        }
        Posture.UNFOLDED -> Column(Modifier.fillMaxSize()) {
            Head(v, Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp), large = true)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 10.dp, top = 14.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Note(tr.t("What to do tomorrow"))
                    if (v.cards.isEmpty()) Empty(tr)
                    for ((i, card) in v.cards.withIndex()) if (i < 2) BigCard(card, tr, onPlaced, onOpen, i) else SmallCard(card, tr, onPlaced, onOpen, i)
                    OrderStrip(v, tr, v.cards.size)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 22.dp, top = 14.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Note(tr.t("How things stand"))
                    AccountCard(v, tr, 1, chartHeight = 120.dp)
                    RingCard(v, tr, 2, Modifier.fillMaxWidth())
                    Links(v)
                    if (picks != null) PicksSection(picks, tr, onPick = onPick)
                }
            }
        }
        Posture.TABLETOP -> Tabletop({
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Head(v, large = true)
                    OrderStrip(v, tr, 0, card = false)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccountCard(v, tr, 0, chartHeight = 110.dp, ring = true)
                }
            }
        }) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (v.cards.isEmpty()) Empty(tr)
                for (row in v.cards.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((i, card) in row.withIndex()) Box(Modifier.weight(1f)) { SmallCard(card, tr, onPlaced, onOpen, i, tall = true) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Links(v)
                if (picks != null) PicksSection(picks, tr, onPick = onPick)
            }
        }
    }
    }
}

/** The date of the plan, the market chip with the EGX30's little line, and the sentence. */
@Composable
private fun Head(v: TodayView, modifier: Modifier = Modifier, large: Boolean = false) {
    val b = LocalBoard.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(v.dateLine, style = Type.small, color = b.muted)
            if (v.market != null) Row(Modifier.clip(Pill).background(b.chip).border(1.dp, b.line, Pill).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(v.market, style = Type.small, color = b.ink)
                if (v.marketCurve.size > 1) Sparkline(v.marketCurve, if (v.marketCurve.last() >= v.marketCurve.first()) b.buy else b.sell, Modifier.width(34.dp).height(14.dp), width = 2.dp)
            }
        }
        Text(v.sentence, style = if (large) Type.sentenceLarge else Type.sentence, color = b.ink, modifier = Modifier.padding(top = 8.dp).arrive(0))
        if (v.monthEnd) Text(LocalMonthEndLink.current.first, style = Type.bodyStrong, color = b.violet,
            modifier = Modifier.padding(top = 4.dp).clip(Pill).clickable(role = Role.Button, onClick = LocalMonthEndLink.current.second).heightIn(min = 40.dp).padding(vertical = 8.dp))
    }
}

@Composable
private fun Links(v: TodayView, modifier: Modifier = Modifier) {
    val b = LocalBoard.current
    Text(LocalDividendsLink.current.first, style = Type.bodyStrong, color = b.ink,
        modifier = modifier.clip(Pill).clickable(role = Role.Button, onClick = LocalDividendsLink.current.second).heightIn(min = 40.dp).padding(vertical = 10.dp))
}

@Composable
private fun Empty(tr: Tr, modifier: Modifier = Modifier) {
    BoardCard(modifier = modifier) {
        Text(tr.t("When a strategy fires at the close, its call shows here with what to do, and a notification says so. Most days nothing does, and then there is nothing to do."),
            style = Type.body, color = LocalBoard.current.muted)
    }
}

/** A call, large: what to do in big type, the stock, the chips, and the button that marks the order placed. */
@Composable
private fun BigCard(card: CardView, tr: Tr, onPlaced: (CardView) -> Unit, onOpen: (CardView) -> Unit, order: Int) {
    val b = LocalBoard.current
    BoardCard(brush = if (card.buy) b.buyBrush else b.sellBrush, order = order, onClick = { onOpen(card) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(card.action, style = Type.action, color = if (card.buy) b.buy else b.sell, modifier = Modifier.weight(1f))
            EgxStrategies.of(card.call.strategy)?.let { Chip(tr.t(it.name), glyph = it.id) }
        }
        Text(card.name, style = Type.name, color = b.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(card.line, style = Type.small, color = b.muted)
        Chips(card.chips, gold = card.chips.lastOrNull().takeIf { card.call.timing.isNotEmpty() })
        Spacer(Modifier.height(2.dp))
        if (card.placed) Row(Modifier.fillMaxWidth().clip(Pill).border(2.dp, if (card.buy) b.buy else b.sell, Pill)
            .clickable(role = Role.Checkbox) { onPlaced(card) }.heightIn(min = 48.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(Modifier.stamped(rememberStamp(true))) { TickGlyph(if (card.buy) b.buy else b.sell, size = 22.dp) }
            Spacer(Modifier.width(8.dp))
            Text(tr.t("Placed the order"), style = Type.bodyStrong, color = if (card.buy) b.buy else b.sell)
        } else PrimaryButton(tr.t("Placed the order")) { onPlaced(card) }
    }
}

/** A call, compact: name and what to do on one line, the chips, and the round mark for the order. */
@Composable
private fun SmallCard(card: CardView, tr: Tr, onPlaced: (CardView) -> Unit, onOpen: (CardView) -> Unit, order: Int, tall: Boolean = false) {
    val b = LocalBoard.current
    BoardCard(brush = if (card.buy) b.buyBrush else b.sellBrush, order = order, onClick = { onOpen(card) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (tall) Text(card.action, style = Type.actionSmall, color = if (card.buy) b.buy else b.sell)
                Text(card.name, style = Type.nameSmall, color = b.ink, maxLines = if (tall) 2 else 1, overflow = TextOverflow.Ellipsis)
                Text(card.line, style = Type.small, color = b.muted, maxLines = 2)
            }
            if (!tall) Text(card.action, style = Type.actionSmall, color = if (card.buy) b.buy else b.sell)
            Spacer(Modifier.width(10.dp))
            PlacedMark(card.placed, if (card.buy) b.buy else b.sell, tr) { onPlaced(card) }
        }
        Chips(card.chips, gold = card.chips.lastOrNull().takeIf { card.call.timing.isNotEmpty() })
    }
}

/**
 * Tomorrow in the order it happens: what sells at the open, then what buys near the close. Shown only when both happen,
 * or when the screen has room for it.
 */
@Composable
private fun OrderStrip(v: TodayView, tr: Tr, order: Int, card: Boolean = true) {
    val b = LocalBoard.current
    val opens = v.cards.filter { it.call.timing == "open" || (!it.buy && it.call.timing.isEmpty()) }.map { it.call.code }
    val closes = v.cards.filter { it.call.timing == "close" }.map { it.call.code }
    if (opens.isEmpty() && closes.isEmpty()) return
    val body: @Composable ColumnScope.() -> Unit = {
        Note(tr.t("Tomorrow's order"))
        if (opens.isNotEmpty()) Step(tr.ltr("10:00"), b.sellBrush, tr.t("At the open: sell %1\$s", opens.joinToString("، ".takeIf { tr.isArabic } ?: ", ")))
        if (opens.isNotEmpty() && closes.isNotEmpty()) Box(Modifier.padding(start = 34.dp).width(2.dp).height(10.dp).background(b.line))
        if (closes.isNotEmpty()) Step(tr.ltr("2:00–2:30"), b.buyBrush, tr.t("Near the close: buy %1\$s", closes.joinToString("، ".takeIf { tr.isArabic } ?: ", ")))
    }
    if (card) BoardCard(order = order, content = body) else Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = body)
}

@Composable
private fun Step(time: String, brush: androidx.compose.ui.graphics.Brush, text: String) {
    val b = LocalBoard.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(70.dp).clip(Pill).background(brush).border(1.dp, b.line, Pill).padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
            Text(time, style = Type.chip, color = b.ink)
        }
        Text(text, style = Type.body, color = b.ink)
    }
}

@Composable
private fun Dots(pager: PagerState, n: Int) {
    val b = LocalBoard.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), horizontalArrangement = Arrangement.Center) {
        repeat(n) { i ->
            val on = i == pager.currentPage
            val w by androidx.compose.animation.core.animateDpAsState(if (on) 22.dp else 7.dp, Motion.slide, label = "dot")
            Box(Modifier.padding(horizontal = 3.dp).height(7.dp).width(w).clip(Pill).background(if (on) b.accent else androidx.compose.ui.graphics.SolidColor(b.line)))
        }
    }
}

/** The account card and the free orders, side by side under the calls. */
@Composable
private fun Tiles(v: TodayView, tr: Tr, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1.55f).fillMaxHeight()) { AccountCard(v, tr, v.cards.size) }
        RingCard(v, tr, v.cards.size + 1, Modifier.weight(1f).fillMaxHeight())
    }
}

/** The paper account: its line against the EGX30 and cash, and the three figures. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCard(v: TodayView, tr: Tr, order: Int, chartHeight: androidx.compose.ui.unit.Dp = 70.dp, ring: Boolean = false) {
    val b = LocalBoard.current
    BoardCard(tr.t("Paper account"), order = order, trailing = if (ring) ({ OrdersRing(v.ordersUsed, FREE_ORDERS, tr, size = 58.dp) }) else null) {
        // The pound sign hangs well below the line: room under it, so it never touches the next line.
        Text(v.account, style = Type.figure, color = b.ink, modifier = Modifier.padding(bottom = 2.dp))
        if (v.you == null) Note(tr.t(Tr.NOT_YET)) else {
            AccountChart(v.youCurve, v.indexCurve, v.cashCurve, height = chartHeight)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(v.barsText.first, style = Type.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = b.buy)
                Text(v.barsText.second, style = Type.small, color = b.violet)
                Text(v.barsText.third, style = Type.small, color = b.muted)
            }
        }
    }
}

@Composable
private fun RingCard(v: TodayView, tr: Tr, order: Int, modifier: Modifier = Modifier) {
    val b = LocalBoard.current
    BoardCard(tr.t("Free orders"), modifier = modifier, order = order) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { OrdersRing(v.ordersUsed, FREE_ORDERS, tr) }
        Text(v.ordersLeft, style = Type.small, color = b.muted, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
