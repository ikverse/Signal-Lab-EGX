package com.ikverse.signallab.egx.view

import android.app.Activity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.ikverse.signallab.egx.EgxGraph
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CopyRow
import com.ikverse.signallab.egx.ledger.Records
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ikverse.signallab.egx.pass.Schedule
import kotlinx.coroutines.launch

/** The pages under the pill menu, in order. */
val PAGES = listOf("Today", "Strategies", "Ledger", "Analyst", "Settings")

@Composable
fun EgxRoot(graph: EgxGraph) {
    val prefs by graph.settings.flow.collectAsState()
    val tr = androidx.compose.runtime.remember(prefs) { graph.tr() }
    val dao = graph.ledger.dao()
    val calls by dao.latestCalls().collectAsState(initial = emptyList())
    val days by dao.allDays(Records.LEVELS).collectAsState(initial = emptyList())
    val yard by dao.yardsticks().collectAsState(initial = emptyList())
    val fills by dao.fills(Records.LEVELS).collectAsState(initial = emptyList())
    val egx30 by produceState(initialValue = emptyList<Pair<Long, Double>>(), calls) { value = graph.store.egx30().map { it.day to it.close } }
    val picks by dao.picks().collectAsState(initial = emptyList())
    val pickEvents by dao.pickEvents().collectAsState(initial = emptyList())
    val pickRecords by dao.pickRecords().collectAsState(initial = emptyList())
    val pickChoices by dao.pickChoices().collectAsState(initial = emptyList())
    val market by dao.lastMarket().collectAsState(initial = null)
    val facts by dao.allFacts().collectAsState(initial = emptyList())
    val headlines by dao.recentHeadlines().collectAsState(initial = emptyList())
    val pickDays by dao.allDays(Records.PICKS).collectAsState(initial = emptyList())
    // The last close of every stock with a pick still open, for "+2.1% since".
    val pickCloses by produceState(initialValue = emptyMap<String, Double>(), picks, pickEvents) {
        val from = EgxTime.today(System.currentTimeMillis()) - 30 * EgxTime.DAY_MS
        value = picks.filter { it.shown }.map { it.code }.distinct().take(60).mapNotNull { c -> graph.store.daily(c, from).lastOrNull()?.let { c to it.close } }.toMap()
    }
    val posture = posture()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // A change of language, money or tonight's calls shows on the home-screen widget too.
    androidx.compose.runtime.LaunchedEffect(prefs, calls) { com.ikverse.signallab.egx.widget.TodayWidget.refresh(context) }
    BoardTheme {
        CompositionLocalProvider(LocalLayoutDirection provides if (tr.isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            BoardPage {
                val pages = rememberPagerState { PAGES.size }
                val bottom = androidx.compose.foundation.layout.PaddingValues(0.dp) // the page already keeps clear of the bars
                var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
                var monthEnd by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                var chosen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
                val rule by dao.allDays(Records.RULE).collectAsState(initial = emptyList())
                val double by dao.allDays(Records.LEVELS_DOUBLE).collectAsState(initial = emptyList())
                val copies by dao.copies().collectAsState(initial = emptyList())
                fun stockName(code: String): String { val s = graph.stocks[code]; return (if (tr.isArabic) s?.nameAr ?: s?.nameEn else s?.nameEn) ?: code }
                val today = todayView(calls, days, yard, fills, prefs, egx30, tr, { code -> stockName(code) to code }, EgxTime.date(EgxTime.today(System.currentTimeMillis())))
                val openCall = calls.firstOrNull { "${it.strategy}|${it.code}|${it.action}" == open }
                val picksToday = picksView(picks, pickEvents, pickRecords, market, facts.associateBy { it.code }, headlines, pickCloses, prefs, tr, ::stockName)
                var pickOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
                val openPick = picks.firstOrNull { "${it.list}|${it.code}|${it.day}" == pickOpen }
                var stock by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
                var dividendsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                when {
                    stock != null -> {
                        val code = stock!!
                        androidx.activity.compose.BackHandler { stock = null }
                        var range by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ChartRange.YEAR) }
                        val rows by produceState(initialValue = emptyList<com.ikverse.signallab.egx.data.DailyRow>(), code) { value = graph.store.daily(code) }
                        val divs by produceState(initialValue = emptyList<com.ikverse.signallab.egx.data.EgxDividend>(), code) { value = graph.store.dividends() }
                        val history by androidx.compose.runtime.remember(code) { dao.callsFor(code) }.collectAsState(initial = emptyList())
                        // The levels drawn are those of the newest buy on the stock while a strategy still holds it.
                        val held = trades(code, fills, tr).any { it.closed == null }
                        val levelsOf = if (held) history.firstOrNull { it.action == "buy" } else null
                        StockPage(stockView(code, stockName(code), rows, divs, history, fills, levelsOf, range, tr), posture, tr, range, { range = it },
                            onDividends = { stock = null; dividendsOpen = true }, callDays = history.map { it.day }.toSet()) { stock = null }
                    }
                    dividendsOpen -> {
                        androidx.activity.compose.BackHandler { dividendsOpen = false }
                        var month by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(java.time.YearMonth.from(EgxTime.date(EgxTime.today(System.currentTimeMillis())))) }
                        val divs by produceState(initialValue = emptyList<com.ikverse.signallab.egx.data.EgxDividend>()) { value = graph.store.dividends() }
                        val closes by produceState(initialValue = emptyMap<String, Double>(), divs) {
                            val from = EgxTime.today(System.currentTimeMillis()) - 30 * EgxTime.DAY_MS
                            value = divs.map { it.code }.distinct().mapNotNull { c -> graph.store.daily(c, from).lastOrNull()?.let { c to it.close } }.toMap()
                        }
                        val run = com.ikverse.signallab.engine.egx.EgxStrategies.dividendRun.id
                        val runOpen = fills.filter { it.strategy == run }.map { it.code }.distinct()
                            .filter { c -> trades(c, fills, tr).any { it.strategy == run && it.closed == null } }.toSet()
                        DividendPage(dividendMonth(divs, closes, runOpen, month, tr, ::stockName), posture, tr, onMonth = { month = it },
                            onStock = { stock = it }, onAdd = { addToCalendar(context, it) }) { dividendsOpen = false }
                    }
                    openPick != null -> {
                        androidx.activity.compose.BackHandler { pickOpen = null }
                        val card = pickCard(openPick, pickEvents, pickRecords, facts.firstOrNull { it.code == openPick.code }, headlines, pickCloses[openPick.code], prefs, tr, stockName(openPick.code))
                        PickPage(card, posture, tr, onBack = { pickOpen = null }, onStock = { pickOpen = null; stock = openPick.code },
                            onShare = { sharePick(context, card.shareText, tr.t("Share as text")) }, chart = chartData(graph, openPick.code))
                    }
                    openCall != null -> {
                        androidx.activity.compose.BackHandler { open = null }
                        CallPage(callView(openCall, stockName(openCall.code), prefs, tr), posture, tr, onBack = { open = null },
                            onCopyAndOpen = { Thndr.copyAndOpen(context, openCall.shares.toString()) },
                            onTook = { price, shares -> scope.launch { dao.addCopy(CopyRow(strategy = openCall.strategy, code = openCall.code, buy = openCall.action == "buy", day = EgxTime.today(System.currentTimeMillis()), shares = shares, price = price)) } },
                            onStock = { stock = openCall.code }, chart = chartData(graph, openCall.code))
                    }
                    monthEnd -> {
                        androidx.activity.compose.BackHandler { monthEnd = false }
                        MonthEndPage(monthEndView(calls, today.ordersUsed, tr), posture, tr, ::stockName, onBack = { monthEnd = false }, onStock = { stock = it }) { call ->
                            scope.launch { dao.setPlaced(call.day, call.strategy, call.code, call.action, !call.placed) }
                        }
                    }
                    else -> Column(Modifier.fillMaxSize()) {
                    val tabs: @Composable () -> Unit = { HeaderTabs(tr, pages) { i -> scope.launch { pages.animateScrollToPage(i) } } }
                    if (posture != Posture.TABLETOP) Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) { tabs() }
                    CompositionLocalProvider(LocalTabRow provides if (posture == Posture.TABLETOP) tabs else null) {
                    HorizontalPager(pages, Modifier.fillMaxWidth().weight(1f), beyondViewportPageCount = 0) { page ->
                        when (page) {
                            0 -> TodayScreen(today, posture, tr,
                                onPlaced = { card -> scope.launch { dao.setPlaced(card.call.day, card.call.strategy, card.call.code, card.call.action, !card.placed) } },
                                onOpen = { card -> open = "${card.call.strategy}|${card.call.code}|${card.call.action}" }, bottom = bottom, onMonthEnd = { monthEnd = true }, onDividends = { dividendsOpen = true },
                                picks = picksToday, onPick = { card -> pickOpen = "${card.pick.list}|${card.pick.code}|${card.pick.day}" })
                            1 -> StrategiesPage(strategyViews(days, yard, fills, tr), posture, tr, chosen, pickEvidence(pickDays, yard, pickRecords, pickChoices, tr)) { chosen = it }
                            2 -> {
                                val lv = ledgerView(mapOf(Records.LEVELS to days, Records.RULE to rule, Records.LEVELS_DOUBLE to double), yard, mapOf(Records.LEVELS to fills), copies,
                                    calls.count { it.action != "keep" }, prefs, tr)
                                val usd by produceState(initialValue = emptyList<Pair<Long, Double>>(), calls) { value = graph.store.macro(com.ikverse.signallab.egx.data.Macro.USD) }
                                val gold by produceState(initialValue = emptyList<Pair<Long, Double>>(), calls) { value = graph.store.macro(com.ikverse.signallab.egx.data.Macro.GOLD) }
                                val end = days.filter { it.strategy in prefs.on }.maxOfOrNull { it.day }
                                val real = realTerms(prefs.liveStart, end, prefs.money, lv.withTargets, lv.accountBars[1].value, lv.accountBars[2].value, usd, gold, tr)
                                LedgerPage(lv, posture, tr, onStock = { stock = it }, real = real)
                            }
                            3 -> {
                                val asking by graph.analyst.state.collectAsState()
                                val answers by dao.answers().collectAsState(initial = emptyList())
                                var paired by androidx.compose.runtime.remember { mutableStateOf(graph.pairing.paired()) }
                                AnalystPage(asking, paired, answers, graph.analyst.conversation.isNotEmpty(), posture, tr,
                                    onProbe = { a, c -> graph.pairing.probe(a, c) },
                                    onConfirm = { graph.pairing.confirm(it); paired = graph.pairing.paired() },
                                    onScan = {
                                        // Backing out of the scanner leaves the card as it was.
                                        com.ikverse.signallab.egx.analyst.scanQr(context)?.let { text ->
                                            graph.pairing.confirm(graph.pairing.probeScanned(com.ikverse.signallab.egx.analyst.ClaudeInQr.parse(text)))
                                            paired = graph.pairing.paired()
                                        }
                                    },
                                    onForget = { graph.pairing.forget(); paired = null },
                                    onAsk = { graph.analyst.ask(it, tr.isArabic) }, onStop = { graph.analyst.stop() }, onNew = { graph.analyst.newConversation() })
                            }
                            else -> {
                                val update by graph.updates.state.collectAsState()
                                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { graph.updates.checkIfDue() } }
                                SettingsPage(prefs, posture, tr, onChange = { graph.settings.update(it) }, onRunNow = { Schedule.runNow(context) },
                                    update = update, version = com.ikverse.signallab.egx.BuildConfig.VERSION_NAME) {
                                    scope.launch {
                                        when (update) {
                                            is com.ikverse.signallab.egx.update.UpdateState.Available, is com.ikverse.signallab.egx.update.UpdateState.NeedsPermission,
                                            is com.ikverse.signallab.egx.update.UpdateState.Handed, is com.ikverse.signallab.egx.update.UpdateState.Failed -> graph.updates.install()
                                            else -> graph.updates.checkNow()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                    }
                }
            }
        }
    }
}

/** A stock's stored closes and the days a strategy made a call on it, for a call's or a pick's chart. */
@Composable
private fun chartData(graph: EgxGraph, code: String): ChartData? {
    val rows by produceState(initialValue = emptyList<com.ikverse.signallab.egx.data.DailyRow>(), code) { value = graph.store.daily(code) }
    val calls by androidx.compose.runtime.remember(code) { graph.ledger.dao().callsFor(code) }.collectAsState(initial = emptyList())
    if (rows.size < 2) return null
    return ChartData(rows.map { it.day }, rows.map { it.close }, calls.map { it.day }.toSet())
}

/**
 * The page names along the top, after EGX Analyzer's header: the current page's name large and in ink, the other four
 * small and grey beside it. **A change of page is the emphasis moving along the row**, following the finger during a swipe,
 * and the row scrolls so the name before the current one still shows at its start. It is the pages' title too.
 */
@Composable
fun HeaderTabs(tr: Tr, pager: androidx.compose.foundation.pager.PagerState, onPick: (Int) -> Unit) {
    val b = LocalBoard.current
    val scroll = androidx.compose.foundation.rememberScrollState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val starts = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    var rowWidth by androidx.compose.runtime.remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(pager.currentPage, starts.size) {
        val target = starts[(pager.currentPage - 1).coerceAtLeast(0)] ?: return@LaunchedEffect
        scroll.animateScrollTo(target)
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).selectableGroup()
            .onSizeChanged { rowWidth = it.width },
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val pos = pager.currentPage + pager.currentPageOffsetFraction
        for ((i, p) in PAGES.withIndex()) {
            val e = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
            Text(
                tr.t(p),
                style = androidx.compose.ui.text.lerp(Type.tabSmall, Type.tabLarge, e),
                color = androidx.compose.ui.graphics.lerp(b.muted, b.ink, e),
                maxLines = 1,
                modifier = Modifier
                    .onPlaced { c ->
                        val x = c.positionInParent().x.toInt()
                        starts[i] = if (rtl) (rowWidth - x - c.size.width).coerceAtLeast(0) else x
                    }
                    .then(if (i == pager.currentPage) Modifier.semantics { heading() } else Modifier)
                    .selectable(selected = i == pager.currentPage, role = Role.Tab) { onPick(i) }
                    .heightIn(min = 44.dp)
                    .padding(vertical = 2.dp),
            )
        }
    }
}

/**
 * How the Fold 7 is held (item 35): half-folded with a horizontal crease is tabletop; a window at least 600 dp wide is
 * unfolded; anything else is folded.
 */
@Composable
fun posture(): Posture {
    val activity = LocalContext.current as? Activity
    val fold by produceState<FoldingFeature?>(null, activity) {
        if (activity == null) return@produceState
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        }
    }
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val f = fold
    return when {
        f != null && f.state == FoldingFeature.State.HALF_OPENED && f.orientation == FoldingFeature.Orientation.HORIZONTAL -> Posture.TABLETOP
        wide -> Posture.UNFOLDED
        else -> Posture.FOLDED
    }
}
