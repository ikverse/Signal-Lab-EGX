package com.ikverse.signallab.egx.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.egx.i18n.Tr

/** A stock's own page: its chart with an open call's levels, its dividends and every paper trade on it. */
@Composable
fun StockPage(v: StockView, posture: Posture, tr: Tr, range: ChartRange, onRange: (ChartRange) -> Unit, onDividends: () -> Unit = {},
    callDays: Set<Long> = emptySet(), onBack: () -> Unit) {
    val b = LocalBoard.current
    val levels = if (v.levels.isEmpty()) null else PlanLevels(
        entryLow = v.levels.filter { it.kind == ChartLevel.Kind.ENTRY }.minOfOrNull { it.price },
        entryHigh = v.levels.filter { it.kind == ChartLevel.Kind.ENTRY }.maxOfOrNull { it.price },
        stop = v.levels.firstOrNull { it.kind == ChartLevel.Kind.STOP }?.price,
        target1 = v.levels.filter { it.kind == ChartLevel.Kind.TARGET }.minOfOrNull { it.price },
        target2 = v.levels.filter { it.kind == ChartLevel.Kind.TARGET }.takeIf { it.size > 1 }?.maxOf { it.price },
    )
    val chart: @Composable ColumnScope.() -> Unit = {
        Back(tr.t("Back"), onBack)
        Title(v.name)
        Note("${v.code} · ${v.lastLine}")
        // The page holds every stored close; the card shows the range chosen.
        ChartCard(ChartData(v.points.map { it.first }, v.points.map { it.second }, callDays), levels, tr, order = 0, height = 190.dp, range = range, onRange = onRange)
        if (v.rangeLine.isNotEmpty()) Text(v.rangeLine, style = Type.body, color = b.ink)
    }
    val details: @Composable ColumnScope.() -> Unit = {
        BoardCard(tr.t("Dividends"), order = 1) {
            if (v.dividends.isEmpty()) Note(tr.t("No dividends recorded for this stock."))
            for ((amount, day) in v.dividends) Kv(day, amount)
            Text(tr.t("Dividend calendar ›"), style = Type.bodyStrong, color = b.ink,
                modifier = Modifier.clickable(role = Role.Button, onClick = onDividends).heightIn(min = 40.dp).padding(vertical = 10.dp))
        }
        BoardCard(tr.t("Paper trades on this stock"), order = 2) {
            Note(v.callsLine)
            if (v.trades.isEmpty()) Note(tr.t("No strategy has held it yet."))
            for (t in v.trades) Text(t.line, style = Type.body, color = if ((t.result ?: 0.0) < 0) b.sell else b.ink)
        }
    }
    Panes(posture, chart, details)
}
