package com.ikverse.signallab.egx.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.ledger.PickRow
import com.ikverse.signallab.engine.Markets
import kotlin.math.abs

/** The four levels of a call or a pick, in prices. Any may be missing; a range has both ends. */
data class PlanLevels(val entryLow: Double?, val entryHigh: Double?, val stop: Double?, val target1: Double?, val target2: Double?) {
    val values: List<Double> get() = listOfNotNull(entryLow, entryHigh, stop, target1, target2)
    val entryTop: Double? get() = entryHigh ?: entryLow
    val isEmpty: Boolean get() = values.isEmpty()

    /** What the far target pays against what the stop costs, from the top of the entry range; null without both. */
    val rewardToRisk: Double?
        get() {
            val top = entryTop ?: return null
            val stop = stop ?: return null
            val target = target2 ?: target1 ?: return null
            val risk = top - stop
            return if (risk > 0 && target > top) (target - top) / risk else null
        }

    companion object {
        fun of(c: CallRow) = PlanLevels(c.entryLow, c.entryHigh, c.stop, c.target1, c.target2)
        fun of(p: PickRow): PlanLevels {
            val m = Markets.EGX
            return PlanLevels(m.round(p.entryLow), m.round(p.entryHigh), m.round(p.stop), p.target1?.let(m::round), p.target2?.let(m::round))
        }
    }
}

// ---- the price chart, after EGX Analyzer's stock sheet ----

/** One figure in the column on the right of the chart: what the level is called, its price, and how to draw it. */
private class ChartTag(val value: Double, val word: String, val figure: String, val color: Color, val filled: Boolean = false, val twoRows: Boolean = false)

/**
 * Where a stock has been, with the levels it is judged against drawn across it, in the way EGX Analyzer's stock sheet
 * draws it: the line on a faint fill, each level a hairline, the entry range a band, a dot wherever a strategy made a call
 * on the stock, the latest close breathing, and every figure in a column of its own on the right, never on the plot, each
 * joined to its line by a short leader. Tags that would overlap are spread by [stackTags].
 *
 * The scale is the price range, grown by at most 40% of its own span to take the levels in; a level beyond that is pinned
 * to the edge it passed and its figure carries an arrow. Charts read left to right in both languages (item 39).
 */
@Composable
fun PriceChart(
    closes: List<Double>,
    levels: PlanLevels?,
    marks: Set<Int>,
    axis: List<String>,
    tr: Tr,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
) {
    if (closes.size < 2) return
    val b = LocalBoard.current
    val priceLow = closes.min()
    val priceHigh = closes.max()
    val priceSpan = (priceHigh - priceLow).takeIf { it > 0.0 } ?: (priceHigh * 0.02).takeIf { it > 0.0 } ?: 1.0
    val room = priceSpan * 0.4
    val wanted = levels?.values.orEmpty()
    val low = maxOf(priceLow - room, minOf(priceLow, wanted.minOrNull() ?: priceLow))
    val high = minOf(priceHigh + room, maxOf(priceHigh, wanted.maxOrNull() ?: priceHigh))
    val span = (high - low).takeIf { it > 0.0 } ?: 1.0
    val latest = closes.last()

    val measurer = rememberTextMeasurer()
    val tags = remember(levels, latest, priceLow, priceHigh, b, tr) {
        buildList {
            if (levels == null || levels.isEmpty) {
                add(ChartTag(priceHigh, tr.t("High"), tr.price(priceHigh), b.ink))
                add(ChartTag(priceLow, tr.t("Low"), tr.price(priceLow), b.ink))
            } else {
                levels.target2?.let { add(ChartTag(it, tr.t("Target 2"), tr.price(it), b.buy)) }
                levels.target1?.let { add(ChartTag(it, tr.t("Target 1"), tr.price(it), b.buy)) }
                val lo = levels.entryLow
                val hi = levels.entryHigh
                if (lo != null && hi != null && lo != hi) add(ChartTag((lo + hi) / 2, tr.t("Entry"), tr.price(minOf(lo, hi)) + "–" + tr.price(maxOf(lo, hi)), b.violet, twoRows = true))
                else (lo ?: hi)?.let { add(ChartTag(it, tr.t("Entry"), tr.price(it), b.violet)) }
                levels.stop?.let { add(ChartTag(it, tr.t("Stop"), tr.price(it), b.sell)) }
            }
            add(ChartTag(latest, tr.t("Now"), tr.price(latest), b.onAccent, filled = true))
        }.sortedByDescending { it.value }.map { tag ->
            val figure = when {
                tag.value > high -> "↑ " + tag.figure
                tag.value < low -> "↓ " + tag.figure
                else -> tag.figure
            }
            Triple(tag, measurer.measure(tag.word, Type.tag.copy(textDirection = TextDirection.Content)), measurer.measure(figure, Type.tagFigure))
        }
    }
    val dates = remember(axis, measurer) { axis.map { measurer.measure(it, Type.small.copy(textDirection = TextDirection.Content)) } }
    val density = LocalDensity.current
    val gutter = with(density) {
        val widest = tags.maxOfOrNull { (t, w, f) -> if (t.twoRows) maxOf(w.size.width, f.size.width).toFloat() else w.size.width + 8.dp.toPx() + f.size.width } ?: 0f
        14.dp.toPx() + widest + 16.dp.toPx()
    }
    val drawn by rememberDraw(closes)
    val breath = rememberBreath()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            modifier.fillMaxWidth().height(height + 24.dp).semantics {
                contentDescription = "${closes.size} · ${tr.price(closes.first())} → ${tr.price(latest)}"
            },
        ) {
            val stroke = 2.5.dp.toPx()
            val plotW = maxOf(size.width - gutter, size.width * 0.55f)
            val plotH = height.toPx()
            val top = stroke
            val usable = plotH - stroke * 2
            fun y(v: Double) = top + ((1.0 - ((v.coerceIn(low, high) - low) / span)) * usable).toFloat()
            val step = plotW / (closes.size - 1)
            val hair = 1.dp.toPx()

            if (levels != null) {
                val lo = levels.entryLow
                val hi = levels.entryHigh
                if (lo != null && hi != null && lo != hi) {
                    val t = y(maxOf(lo, hi))
                    val bt = y(minOf(lo, hi))
                    drawRect(b.violet.copy(alpha = 0.16f), Offset(0f, t), Size(plotW, maxOf(bt - t, 4.dp.toPx())))
                } else (lo ?: hi)?.let { drawLine(b.violet.copy(alpha = 0.6f), Offset(0f, y(it)), Offset(plotW, y(it)), hair) }
                for (t in listOfNotNull(levels.target1, levels.target2)) drawLine(b.buy.copy(alpha = 0.55f), Offset(0f, y(t)), Offset(plotW, y(t)), hair)
                levels.stop?.let { drawLine(b.sell.copy(alpha = 0.7f), Offset(0f, y(it)), Offset(plotW, y(it)), hair) }
            }

            val path = Path()
            closes.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * step, y(v)) }
            val area = Path().apply { addPath(path); lineTo(plotW, plotH); lineTo(0f, plotH); close() }
            clipRect(right = plotW * drawn + stroke, bottom = plotH) {
                drawPath(area, Brush.verticalGradient(listOf(b.accent1.copy(alpha = 0.28f), b.accent1.copy(alpha = 0f)), endY = plotH))
                drawPath(path, Brush.horizontalGradient(listOf(b.accent1, b.accent2), endX = plotW),
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val ring = 2.dp.toPx()
            for (i in marks) if (i in closes.indices && i * step <= plotW * drawn + stroke) {
                val at = Offset(i * step, y(closes[i]))
                drawCircle(b.cardTop, 4.5.dp.toPx() + ring, at)
                drawCircle(b.ink, 4.5.dp.toPx(), at)
            }
            if (drawn >= 0.99f) {
                val now = Offset(plotW, y(latest))
                drawCircle(b.cardTop, 5.dp.toPx() * breath + ring, now)
                drawCircle(Brush.linearGradient(listOf(b.accent1, b.accent2), now - Offset(6f, 6f), now + Offset(6f, 6f)), 5.dp.toPx() * breath, now)
            }

            val row = 20.dp.toPx()
            val pad = 8.dp.toPx()
            val left = plotW + 14.dp.toPx()
            val tops = stackTags(tags.map { y(it.first.value) }, tags.map { if (it.first.twoRows) row * 2 else row }, plotH)
            tags.forEachIndexed { i, (tag, word, figure) ->
                val rowTop = tops[i]
                val middle = rowTop + row / 2
                fun centred(h: Int, at: Float) = at + (row - h) / 2
                if (tag.filled) {
                    drawRoundRect(Brush.horizontalGradient(listOf(b.accent1, b.accent2), startX = left, endX = size.width),
                        Offset(left, rowTop + 1.dp.toPx()), Size(size.width - left, row - 2.dp.toPx()), CornerRadius(row / 2))
                    drawText(word, b.onAccent, Offset(left + pad, centred(word.size.height, rowTop)))
                    drawText(figure, b.onAccent, Offset(size.width - pad - figure.size.width, centred(figure.size.height, rowTop)))
                } else {
                    val elbow = Path().apply { moveTo(plotW, y(tag.value)); lineTo(plotW + 7.dp.toPx(), middle); lineTo(left + pad / 2, middle) }
                    drawPath(elbow, tag.color.copy(alpha = 0.6f), style = Stroke(width = hair))
                    drawText(word, b.muted, Offset(left + pad, centred(word.size.height, rowTop)))
                    val figureRow = if (tag.twoRows) rowTop + row else rowTop
                    drawText(figure, tag.color, Offset(size.width - figure.size.width, centred(figure.size.height, figureRow)))
                }
            }

            if (dates.size == 3) {
                val axisTop = plotH + 6.dp.toPx()
                val (first, mid, last) = dates
                drawText(first, b.muted, Offset(0f, axisTop))
                val lastLeft = plotW - last.size.width
                drawText(last, b.muted, Offset(lastLeft, axisTop))
                val midLeft = (closes.size / 2) * step - mid.size.width / 2
                if (midLeft > first.size.width + pad * 2 && midLeft + mid.size.width < lastLeft - pad * 2) drawText(mid, b.muted, Offset(midLeft, axisTop))
            }
        }
    }
}

/**
 * Where each tag's top edge goes, so none overlaps another and none leaves the chart. [centres] is where each would sit
 * alone, top to bottom; tags that would overlap are joined into one block centred on where its members wanted to be.
 */
fun stackTags(centres: List<Float>, heights: List<Float>, total: Float): List<Float> {
    class Block(val first: Int, val last: Int, val height: Float) {
        val top: Float

        init {
            var offset = 0f
            var sum = 0f
            for (i in first..last) {
                sum += centres[i] - heights[i] / 2 - offset
                offset += heights[i]
            }
            top = (sum / (last - first + 1)).coerceIn(0f, maxOf(0f, total - height))
        }
    }
    val blocks = ArrayList<Block>()
    for (i in centres.indices) {
        var block = Block(i, i, heights[i])
        while (blocks.isNotEmpty() && blocks.last().top + blocks.last().height > block.top) {
            val above = blocks.removeAt(blocks.lastIndex)
            block = Block(above.first, block.last, above.height + block.height)
        }
        blocks.add(block)
    }
    val tops = FloatArray(centres.size)
    for (block in blocks) {
        var at = block.top
        for (i in block.first..block.last) {
            tops[i] = at
            at += heights[i]
        }
    }
    return tops.toList()
}

// ---- the price ladder ----

/** One rung of the ladder: its price, its name, what to do there, and which kind it is. */
data class Rung(val price: Double, val name: String, val line: String, val kind: ChartLevel.Kind, val priceText: String = "")

/** The rungs of a call: the targets, the entry range and the stop, with what each one means. */
fun callRungs(c: CallRow, tr: Tr): List<Rung> = buildList {
    c.target2?.let { add(Rung(it, tr.t("Target 2"), tr.t("Sell the rest"), ChartLevel.Kind.TARGET)) }
    c.target1?.let { add(Rung(it, tr.t("Target 1"), tr.t("Sell half · %1\$d shares", c.shares / 2), ChartLevel.Kind.TARGET)) }
    if (c.entryHigh != null && c.entryLow != null) add(Rung(c.entryHigh, tr.t("Entry range"), tr.t("Last close %1\$s · above the range, wait", tr.price(c.close)), ChartLevel.Kind.ENTRY,
        tr.price(c.entryLow) + "–" + tr.price(c.entryHigh)))
    c.stop?.let { add(Rung(it, tr.t("Stop loss"), tr.t("Sell if a day closes below"), ChartLevel.Kind.STOP)) }
}

/** The rungs of a technical pick. */
fun pickRungs(p: PickRow, tr: Tr): List<Rung> {
    val l = PlanLevels.of(p)
    return buildList {
        l.target2?.let { add(Rung(it, tr.t("Target 2"), tr.t("Sell the rest"), ChartLevel.Kind.TARGET)) }
        l.target1?.let { add(Rung(it, tr.t("Target 1"), tr.t("Sell half"), ChartLevel.Kind.TARGET)) }
        add(Rung(l.entryHigh!!, tr.t("Entry range"), tr.t("The order stands three sessions"), ChartLevel.Kind.ENTRY, tr.price(l.entryLow!!) + "–" + tr.price(l.entryHigh)))
        add(Rung(l.stop!!, tr.t("Stop loss"), tr.t("Sell if a day closes below"), ChartLevel.Kind.STOP))
    }
}

/**
 * The four levels on one upright track, **drawn to the real price scale**: the green stretch above the entry against the
 * rose one below is the reward against the risk, as a length. Each rung shows how far it is from the top of the entry
 * range, its price and name, and what to do there. The "now" marker rides up to the entry, the track grows out of it both
 * ways, then the stop and the first target arrive with their rows, then the second target.
 */
@Composable
fun PriceLadder(rungs: List<Rung>, close: Double, tr: Tr, modifier: Modifier = Modifier, height: Dp = 260.dp) {
    if (rungs.isEmpty()) return
    val b = LocalBoard.current
    val entry = rungs.firstOrNull { it.kind == ChartLevel.Kind.ENTRY }?.price ?: close
    val prices = rungs.map { it.price } + close
    val lo = prices.min()
    val hi = prices.max()
    val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    val grow by rememberDraw(rungs, delayMs = 300, ms = 650)
    val halo = rememberHalo()
    val ride by rememberArrival(0, rungs)
    val rowH = 44.dp
    val density = LocalDensity.current
    val pad = 9.dp
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val track = height - pad * 2
        fun yOf(p: Double): Dp = pad + track * ((hi - p) / span).toFloat()
        val rowPx = with(density) { rowH.toPx() }
        val tops = remember(rungs, height) {
            with(density) { stackTags(rungs.map { yOf(it.price).toPx() }, rungs.map { rowPx }, height.toPx()) }
        }
        // The track, between the percent column and the rows; it sits on the start side in either language.
        Canvas(Modifier.width(20.dp).fillMaxHeight().offset(x = 62.dp)) {
            val w = size.width
            val cx = w / 2
            val ty = { p: Double -> yOf(p).toPx() }
            val bar = 8.dp.toPx()
            drawRoundRect(b.line, Offset(cx - bar / 2, ty(hi)), Size(bar, ty(lo) - ty(hi)), CornerRadius(bar / 2))
            val e = ty(entry)
            val topY = ty(rungs.filter { it.kind == ChartLevel.Kind.TARGET }.maxOfOrNull { it.price } ?: entry)
            val botY = ty(rungs.firstOrNull { it.kind == ChartLevel.Kind.STOP }?.price ?: entry)
            val up = (e - topY) * grow
            val down = (botY - e) * grow
            if (up > 0) drawRoundRect(Brush.verticalGradient(listOf(b.rise1, b.rise2), startY = e, endY = e - up), Offset(cx - bar / 2, e - up), Size(bar, up), CornerRadius(bar / 2))
            if (down > 0) drawRoundRect(Brush.verticalGradient(listOf(b.fall1, b.fall2), startY = e, endY = e + down), Offset(cx - bar / 2, e), Size(bar, down), CornerRadius(bar / 2))
            for ((i, r) in rungs.withIndex()) {
                if (r.kind == ChartLevel.Kind.ENTRY) continue
                val show = ((grow - 0.6f) / 0.4f - if (i == 0 && r.kind == ChartLevel.Kind.TARGET && rungs.count { it.kind == ChartLevel.Kind.TARGET } > 1) 0.5f else 0f).coerceIn(0f, 1f)
                if (show <= 0f) continue
                val at = Offset(cx, ty(r.price))
                val color = if (r.kind == ChartLevel.Kind.STOP) b.fall1 else b.rise1
                drawCircle(b.cardTop, 9.dp.toPx() * show, at)
                drawCircle(color, 6.dp.toPx() * show, at)
            }
            // The "now" marker: it rides up to the close, then a halo keeps leaving it.
            val nowY = ty(close) + (1f - ride) * 60.dp.toPx()
            val at = Offset(cx, nowY)
            if (halo > 0f) drawCircle(b.violet.copy(alpha = 0.45f * (1f - halo)), 10.dp.toPx() * (1f + halo), at)
            drawCircle(b.cardTop, 11.dp.toPx(), at)
            drawCircle(Brush.linearGradient(listOf(b.accent1, b.accent2), at - Offset(10f, 10f), at + Offset(10f, 10f)), 8.dp.toPx(), at)
        }
        for ((i, r) in rungs.withIndex()) {
            val top = with(density) { tops[i].toDp() }
            val color = when (r.kind) { ChartLevel.Kind.STOP -> b.sell; ChartLevel.Kind.ENTRY -> b.violet; else -> b.buy }
            val order = when (r.kind) { ChartLevel.Kind.ENTRY -> 1; ChartLevel.Kind.STOP -> 5; else -> if (i == 0 && rungs.count { it.kind == ChartLevel.Kind.TARGET } > 1) 9 else 5 }
            val pct = if (r.kind == ChartLevel.Kind.ENTRY) tr.t("Now") else signedPercent(r.price / (rungs.firstOrNull { it.kind == ChartLevel.Kind.ENTRY }?.price ?: close) - 1, tr)
            Text(pct, style = Type.bodyStrong.copy(textDirection = TextDirection.Content), color = color, maxLines = 1,
                modifier = Modifier.width(56.dp).offset(y = top + 12.dp).slideIn(order, rungs))
            Column(Modifier.offset(x = 92.dp, y = top).fillMaxWidth().padding(end = 92.dp).slideIn(order, rungs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr.ltr(r.priceText.ifEmpty { tr.price(r.price) }), style = Type.nameSmall, color = color, maxLines = 1)
                    Text(r.name, style = Type.body, color = if (r.kind == ChartLevel.Kind.TARGET) b.ink else color, maxLines = 1)
                }
                Text(r.line, style = Type.small, color = b.muted, maxLines = 1)
            }
        }
    }
}

/** "+20.0%" or "−8.0%", kept left to right inside Arabic. */
fun signedPercent(x: Double, tr: Tr): String = tr.ltr((if (x >= 0) "+" else "−") + tr.percent(abs(x), 1))

// ---- smaller charts ----

/** The paper account against the EGX30 and cash, as growth since the ledger started; each line draws itself in. */
@Composable
fun AccountChart(you: List<Double>, index: List<Double>, cash: List<Double>, modifier: Modifier = Modifier, height: Dp = 72.dp) {
    val b = LocalBoard.current
    val all = (you + index + cash).filter { !it.isNaN() }
    if (all.size < 2) return
    val lo = minOf(0.0, all.min())
    val hi = maxOf(all.max(), lo + 0.01)
    val drawn by rememberDraw(you.size, delayMs = 250)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(modifier.fillMaxWidth().height(height)) {
            fun line(v: List<Double>, color: Color, width: Float, dashed: Boolean) {
                val pts = v.withIndex().filter { !it.value.isNaN() }
                if (pts.size < 2) return
                val n = (v.size - 1).coerceAtLeast(1)
                val p = Path()
                for ((k, iv) in pts.withIndex()) {
                    val x = size.width * iv.index / n
                    val y = (size.height - width) * (1 - ((iv.value - lo) / (hi - lo))).toFloat() + width / 2
                    if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                clipRect(right = size.width * drawn) {
                    drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2f, width * 3)) else null))
                }
            }
            line(cash, b.muted, 2.5.dp.toPx(), dashed = true)
            line(index, b.violet, 2.5.dp.toPx(), dashed = false)
            line(you, b.buy, 4.dp.toPx(), dashed = false)
        }
    }
}

/** A small line of a series, in one colour, for a strategy's card and the market chip. */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier, width: Dp = 4.dp) {
    if (values.size < 2) return
    val lo = values.min()
    val hi = values.max().let { if (it - lo < 1e-9) lo + 1 else it }
    val drawn by rememberDraw(values.size, delayMs = 200)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(modifier) {
            val w = width.toPx()
            val p = Path()
            values.forEachIndexed { i, v ->
                val x = size.width * i / (values.size - 1)
                val y = (size.height - w) * (1 - ((v - lo) / (hi - lo))).toFloat() + w / 2
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            clipRect(right = size.width * drawn) { drawPath(p, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}

/** The free orders used this month, as a ring that fills from the top, with the count inside. */
@Composable
fun OrdersRing(used: Int, total: Int, tr: Tr, modifier: Modifier = Modifier, size: Dp = 78.dp) {
    val b = LocalBoard.current
    val drawn by rememberDraw(used, delayMs = 300)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = 8.dp.toPx()
            drawCircle(b.line, (this.size.minDimension - w) / 2, style = Stroke(w))
            val share = (used.toFloat() / total).coerceIn(0f, 1f) * drawn
            drawArc(Brush.sweepGradient(listOf(b.accent1, b.accent2, b.accent1)), -90f, 360f * share, false,
                topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(tr.ltr(tr.digits("$used/$total")), style = Type.figureSmall, color = b.ink)
    }
}

/** A dot the size of a letter, in a strategy's or a line's colour, for legends. */
@Composable
fun Dot(color: Color, size: Dp = 7.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))
