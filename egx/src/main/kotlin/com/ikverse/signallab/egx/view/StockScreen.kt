package com.ikverse.signallab.egx.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.egx.i18n.Tr

/** A stock's own page: its chart with an open call's levels, its dividends and every paper trade on it. */
@Composable
fun StockPage(v: StockView, posture: Posture, tr: Tr, range: ChartRange, onRange: (ChartRange) -> Unit, onBack: () -> Unit) {
    val c = LocalCalm.current
    val chart: @Composable ColumnScope.() -> Unit = {
        Back(tr.t("Back"), onBack)
        Title(v.name)
        Note("${v.code} · ${v.lastLine}")
        CalmCard {
            Seg(ChartRange.entries.map { rangeName(it, tr) }, ChartRange.entries.indexOf(range)) { onRange(ChartRange.entries[it]) }
            if (v.points.size < 2) Note(tr.t("No prices stored yet."))
            else {
                PriceChart(v.points.map { it.second }, v.levels)
                if (v.rangeLine.isNotEmpty()) Text(v.rangeLine, style = Type.body, color = c.ink)
            }
            if (v.levels.isNotEmpty()) for (l in v.levels.distinctBy { it.name }) {
                Kv(l.name, tr.price(if (l.kind == ChartLevel.Kind.ENTRY) v.levels.filter { it.kind == ChartLevel.Kind.ENTRY }.minOf { it.price } else l.price))
            }
        }
    }
    val details: @Composable ColumnScope.() -> Unit = {
        CalmCard(tr.t("Dividends")) {
            if (v.dividends.isEmpty()) Note(tr.t("No dividends recorded for this stock."))
            for ((amount, day) in v.dividends) Kv(day, amount)
        }
        CalmCard(tr.t("Paper trades on this stock")) {
            Note(v.callsLine)
            if (v.trades.isEmpty()) Note(tr.t("No strategy has held it yet."))
            for (t in v.trades) Text(t.line, style = Type.body, color = if ((t.result ?: 0.0) < 0) c.down else c.ink)
        }
    }
    Panes(posture, chart, details)
}

/** The closes as a line, with the levels as dashed lines across. Always left to right, as the other charts (item 39). */
@Composable
fun PriceChart(closes: List<Double>, levels: List<ChartLevel>) {
    val c = LocalCalm.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val all = closes + levels.map { it.price }
            val lo = all.min()
            val hi = all.max().let { if (it - lo < 1e-9) lo + 1 else it }
            fun y(p: Double) = (size.height * (1 - (p - lo) / (hi - lo))).toFloat()
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            for (l in levels) {
                val color = when (l.kind) { ChartLevel.Kind.STOP -> c.down; ChartLevel.Kind.ENTRY -> c.up; ChartLevel.Kind.TARGET -> c.muted }
                drawLine(color, Offset(0f, y(l.price)), Offset(size.width, y(l.price)), strokeWidth = 2f, pathEffect = dash)
            }
            val step = size.width / (closes.size - 1)
            val path = Path()
            closes.forEachIndexed { i, p -> if (i == 0) path.moveTo(0f, y(p)) else path.lineTo(i * step, y(p)) }
            drawPath(path, c.ink, style = Stroke(width = 3f, cap = StrokeCap.Round))
        }
    }
}
