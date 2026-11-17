package com.ikverse.signallab.egx.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    return TodayView(
        dateLine, market, NotificationTexts.sentence(calls, tr), cards, tr.t("EGP %1\$s", tr.pounds(account)), you, index, cash,
        Triple(tr.t("You %1\$s", signed(you)), tr.t("EGX30 %1\$s", signed(index)), tr.t("Cash %1\$s", signed(cash))),
        used, tr.t("%1\$d left this month", maxOf(0, FREE_ORDERS - used)), calls.any { it.monthEnd },
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
    val c = LocalCalm.current
    androidx.compose.runtime.CompositionLocalProvider(LocalMonthEndLink provides (tr.t("Month-end list") + " ›" to onMonthEnd), LocalDividendsLink provides (tr.t("Dividend calendar ›") to onDividends)) {
    when (posture) {
        Posture.FOLDED -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottom.calculateBottomPadding() + 80.dp)) {
            Head(v, Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp))
            Spacer(Modifier.height(12.dp))
            if (v.cards.isEmpty()) Empty(tr, Modifier.padding(horizontal = 22.dp)) else {
                val pager = rememberPagerState { v.cards.size }
                HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 22.dp), pageSpacing = 12.dp) { i ->
                    BigCard(v.cards[i], tr, onPlaced, onOpen)
                }
                Dots(pager, v.cards.size)
            }
            Tiles(v, tr, Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp))
            if (picks != null) PicksSection(picks, tr, Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp), onPick)
        }
        Posture.UNFOLDED -> Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(22.dp)) {
                Head(v)
                Spacer(Modifier.weight(1f))
                Tiles(v, tr, Modifier.padding(bottom = 70.dp))
            }
            if (v.cards.isEmpty() && picks == null) Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) { Empty(tr) }
            else Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (v.cards.isEmpty()) Empty(tr)
                for (card in v.cards) SmallCard(card, onPlaced, onOpen)
                if (picks != null) PicksSection(picks, tr, onPick = onPick)
                Spacer(Modifier.height(70.dp))
            }
        }
        Posture.TABLETOP -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Head(v, Modifier.weight(1f).padding(22.dp))
                Box(Modifier.weight(1f).fillMaxHeight().padding(22.dp), contentAlignment = Alignment.Center) { Tiles(v, tr) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (v.cards.isEmpty()) Empty(tr)
                    for (row in v.cards.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (card in row) Box(Modifier.weight(1f)) { SmallCard(card, onPlaced, onOpen) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    if (picks != null) PicksSection(picks, tr, onPick = onPick)
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
    }
}

@Composable
private fun Head(v: TodayView, modifier: Modifier = Modifier) {
    val c = LocalCalm.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(v.dateLine, style = Type.small, color = c.muted)
            if (v.market != null) Text(v.market, style = Type.small, color = c.ink,
                modifier = Modifier.clip(RoundedCornerShape(100)).background(c.dock).border(1.dp, c.line, RoundedCornerShape(100)).padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Text(v.sentence, style = Type.sentence, color = c.ink, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        if (v.monthEnd) Text(LocalMonthEndLink.current.first, style = Type.body, color = c.ink,
            modifier = Modifier.padding(top = 4.dp).clickable(role = Role.Button, onClick = LocalMonthEndLink.current.second))
        Text(LocalDividendsLink.current.first, style = Type.small, color = c.muted,
            modifier = Modifier.padding(top = 6.dp).clickable(role = Role.Button, onClick = LocalDividendsLink.current.second))
    }
}

@Composable
private fun Empty(tr: Tr, modifier: Modifier = Modifier) {
    Text(tr.t("When a strategy fires at the close, its call shows here with what to do, and a notification says so. Most days nothing does, and then there is nothing to do."),
        style = Type.body, color = LocalCalm.current.muted, modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(chips: List<String>) {
    val c = LocalCalm.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (chip in chips) Text(chip, style = Type.chip, color = c.ink,
            modifier = Modifier.border(1.dp, c.muted, RoundedCornerShape(100)).padding(horizontal = 11.dp, vertical = 4.dp))
    }
}

@Composable
private fun BigCard(card: CardView, tr: Tr, onPlaced: (CardView) -> Unit, onOpen: (CardView) -> Unit) {
    val c = LocalCalm.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(34.dp)).background(if (card.buy) c.buyCard else c.sellCard)
            .clickable(role = Role.Button) { onOpen(card) }.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(card.action, style = Type.action, color = if (card.buy) c.up else c.down)
        Text(card.name, style = Type.name, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(card.line, style = Type.small, color = c.muted)
        Spacer(Modifier.height(6.dp))
        Chips(card.chips)
        Spacer(Modifier.height(10.dp))
        Text(
            if (card.placed) "✓ " + tr.t("Placed the order") else tr.t("Placed the order"),
            style = Type.body, color = c.actionInk,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(100)).background(c.action)
                .clickable(role = Role.Checkbox) { onPlaced(card) }.padding(12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SmallCard(card: CardView, onPlaced: (CardView) -> Unit, onOpen: (CardView) -> Unit) {
    val c = LocalCalm.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(if (card.buy) c.buyCard else c.sellCard)
            .clickable(role = Role.Button) { onOpen(card) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(card.name, style = Type.nameSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.line, style = Type.small, color = c.muted, maxLines = 2)
            }
            Text(card.action, style = Type.actionSmall, color = if (card.buy) c.up else c.down)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Chips(card.chips) }
            Box(
                Modifier.size(30.dp).clip(CircleShape).border(2.dp, c.muted, CircleShape)
                    .background(if (card.placed) c.action else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Checkbox) { onPlaced(card) },
                contentAlignment = Alignment.Center,
            ) { if (card.placed) Text("✓", color = c.actionInk, style = Type.chip) }
        }
    }
}

@Composable
private fun Dots(pager: PagerState, n: Int) {
    val c = LocalCalm.current
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), horizontalArrangement = Arrangement.Center) {
        repeat(n) { i ->
            Box(Modifier.padding(horizontal = 3.dp).height(7.dp).width(if (i == pager.currentPage) 22.dp else 7.dp).clip(RoundedCornerShape(7.dp))
                .background(if (i == pager.currentPage) c.ink else c.line))
        }
    }
}

@Composable
private fun Tiles(v: TodayView, tr: Tr, modifier: Modifier = Modifier) {
    val c = LocalCalm.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1.5f).clip(RoundedCornerShape(28.dp)).background(c.surface).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(tr.t("Paper account"), style = Type.small, color = c.muted)
            // The pound sign (ج) hangs well below the line: room under it, so it never touches the next line.
            Text(v.account, style = Type.tile, color = c.ink, modifier = Modifier.padding(bottom = 6.dp))
            if (v.you == null) Text(tr.t(Tr.NOT_YET), style = Type.small, color = c.muted) else {
                val top = listOfNotNull(v.you, v.index, v.cash).maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: 1.0
                Bar(v.barsText.first, v.you, top, 1f)
                Bar(v.barsText.second, v.index, top, 0.55f)
                Bar(v.barsText.third, v.cash, top, 0.3f)
            }
        }
        Column(Modifier.weight(1f).clip(RoundedCornerShape(28.dp)).background(c.surface).padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(tr.t("Free orders"), style = Type.small, color = c.muted)
            Text(tr.digits("${v.ordersUsed} / $FREE_ORDERS"), style = Type.tile, color = c.ink)
            Text(v.ordersLeft, style = Type.small, color = c.muted)
        }
    }
}

@Composable
private fun Bar(label: String, value: Double?, top: Double, alpha: Float) {
    val c = LocalCalm.current
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val share = ((value ?: 0.0).coerceAtLeast(0.0) / top).toFloat().coerceIn(0.02f, 1f)
        Box(Modifier.weight(1f).height(6.dp)) {
            Box(Modifier.fillMaxWidth(share).height(6.dp).clip(RoundedCornerShape(6.dp)).background(c.up.copy(alpha = alpha)))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.small, color = c.muted)
    }
}
