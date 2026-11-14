package com.ikverse.signallab.egx.view

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import com.ikverse.signallab.egx.ledger.Records
import com.ikverse.signallab.egx.pass.Schedule
import kotlinx.coroutines.launch

/** The pages under the pill menu, in order. */
val PAGES = listOf("Today", "Strategies", "Ledger", "Settings")

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
    val posture = posture()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    CalmTheme {
        CompositionLocalProvider(LocalLayoutDirection provides if (tr.isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            CalmPage(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                val pages = rememberPagerState { PAGES.size }
                val bottom = WindowInsets.navigationBars.asPaddingValues()
                HorizontalPager(pages, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
                    when (page) {
                        0 -> {
                            val v = todayView(calls, days, yard, fills, prefs, egx30, tr, { code ->
                                val s = graph.stocks[code]
                                ((if (tr.isArabic) s?.nameAr ?: s?.nameEn else s?.nameEn) ?: code) to code
                            }, EgxTime.date(EgxTime.today(System.currentTimeMillis())))
                            TodayScreen(v, posture, tr,
                                onPlaced = { card -> scope.launch { dao.setPlaced(card.call.day, card.call.strategy, card.call.code, card.call.action, !card.placed) } },
                                onOpen = { }, bottom = bottom)
                        }
                        else -> Waiting(tr, PAGES[page]) { Schedule.runNow(context) }
                    }
                }
                Dock(tr, pages.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { i -> scope.launch { pages.animateScrollToPage(i) } }
            }
        }
    }
}

/** A page whose design waits for the owner's acceptance (build item 30). */
@Composable
private fun Waiting(tr: Tr, title: String, onRun: () -> Unit) {
    val c = LocalCalm.current
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text(tr.t(title), style = Type.sentence, color = c.ink)
        Text(tr.t("Run the evening pass now"), style = Type.body, color = c.muted, modifier = Modifier.padding(top = 24.dp).clickable(role = Role.Button, onClick = onRun))
    }
}

/** The floating pill menu, within thumb reach. */
@Composable
private fun Dock(tr: Tr, current: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val c = LocalCalm.current
    Row(modifier.clip(RoundedCornerShape(100)).background(c.dock).border(1.dp, c.dockEdge, RoundedCornerShape(100)).padding(6.dp)) {
        for ((i, p) in PAGES.withIndex()) {
            val on = i == current
            Text(tr.t(p), style = Type.menu, color = if (on) c.actionInk else c.muted,
                modifier = Modifier.clip(RoundedCornerShape(100)).background(if (on) c.action else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Tab) { onPick(i) }.padding(horizontal = 14.dp, vertical = 9.dp))
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
