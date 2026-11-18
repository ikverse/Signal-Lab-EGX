package com.ikverse.signallab.egx.view

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.egx.EgxPrefs
import com.ikverse.signallab.egx.Language
import com.ikverse.signallab.egx.data.EgxTime
import com.ikverse.signallab.egx.i18n.Tr
import com.ikverse.signallab.egx.ledger.CallRow
import com.ikverse.signallab.egx.update.UpdateState
import com.ikverse.signallab.engine.egx.EgxStrategies
import com.ikverse.signallab.engine.strategy.Tier

// ---- parts shared by the screens ----

val CardShape = RoundedCornerShape(22.dp)
val Pill = RoundedCornerShape(100)

/**
 * The page-name row, handed to pages that place it themselves: on a half-folded phone it sits at the top of the flat half,
 * where the thumb is, so a page's tabletop layout draws it there. Null where the shell draws it above the page.
 */
val LocalTabRow = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

@Composable
fun Scroll(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/**
 * One pane folded; two side by side when the Fold is open; above and below the crease when it is half-folded, with the
 * page-name row at the top of the lower half.
 */
@Composable
fun Panes(posture: Posture, first: @Composable ColumnScope.() -> Unit, second: @Composable ColumnScope.() -> Unit) {
    when (posture) {
        Posture.FOLDED -> Scroll { first(); second() }
        Posture.UNFOLDED -> Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { Scroll(Modifier.padding(end = 0.dp)) { first() } }
            Box(Modifier.weight(1f).fillMaxHeight()) { Scroll { second() } }
        }
        Posture.TABLETOP -> Tabletop({ Scroll { first() } }) { Scroll { second() } }
    }
}

/** The two halves of a half-folded phone: [upper] to look at, [lower] to touch, with the page names at the top of it. */
@Composable
fun Tabletop(upper: @Composable () -> Unit, lower: @Composable () -> Unit) {
    val b = LocalBoard.current
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { upper() }
        Box(Modifier.fillMaxWidth().height(1.dp).background(b.line))
        Column(Modifier.weight(1f).fillMaxWidth()) {
            LocalTabRow.current?.let { Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) { it() } }
            Box(Modifier.weight(1f).fillMaxWidth()) { lower() }
        }
    }
}

/** A card: a hairline edge, a soft shadow and a gentle gradient; it arrives [order] after the page's first card. */
@Composable
fun BoardCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    brush: Brush? = null,
    order: Int = 0,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val b = LocalBoard.current
    val source = remember { MutableInteractionSource() }
    Column(
        modifier.fillMaxWidth().arrive(order)
            .then(if (onClick != null) Modifier.pressable(source) else Modifier)
            .shadow(if (b.dark) 0.dp else 3.dp, CardShape, ambientColor = Color(0x33282218), spotColor = Color(0x33282218))
            .clip(CardShape).background(brush ?: b.cardBrush).border(1.dp, b.line, CardShape)
            .then(if (onClick != null) Modifier.clickable(source, null, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null || trailing != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (title != null) Text(title, style = Type.small, color = b.muted, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

@Composable
fun Kv(k: String, v: String) {
    val b = LocalBoard.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = Type.body, color = b.ink, modifier = Modifier.weight(1f))
        Text(v, style = Type.bodyStrong, color = b.ink)
    }
}

@Composable
fun Note(text: String) = Text(text, style = Type.small, color = LocalBoard.current.muted)

@Composable
fun Title(text: String, color: Color = LocalBoard.current.ink) = Text(text, style = Type.title, color = color)

@Composable
fun Lead(text: String) = Text(text, style = Type.lead, color = LocalBoard.current.ink)

@Composable
fun Back(text: String, onBack: () -> Unit) {
    val b = LocalBoard.current
    Row(Modifier.clip(Pill).clickable(role = Role.Button, onClick = onBack).heightIn(min = 40.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BackGlyph(b.muted)
        Text(text, style = Type.body, color = b.muted)
    }
}

/** The one thing to press on a screen: the gradient pill with its glow. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, tall: Boolean = false, onClick: () -> Unit) {
    val b = LocalBoard.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth().pressable(source).shadow(10.dp, Pill, ambientColor = b.accent1, spotColor = b.accent1)
            .clip(Pill).background(b.accent).clickable(source, null, role = Role.Button, onClick = onClick)
            .heightIn(min = if (tall) 58.dp else 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = if (tall) Type.nameSmall else Type.bodyStrong, color = b.onAccent, textAlign = TextAlign.Center) }
}

@Composable
fun SecondButton(text: String, modifier: Modifier = Modifier, tall: Boolean = false, onClick: () -> Unit) {
    val b = LocalBoard.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth().pressable(source).clip(Pill).background(b.cardBrush).border(1.dp, b.line, Pill)
            .clickable(source, null, role = Role.Button, onClick = onClick)
            .heightIn(min = if (tall) 58.dp else 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = if (tall) Type.nameSmall else Type.bodyStrong, color = b.ink, textAlign = TextAlign.Center) }
}

@Composable
fun Chip(text: String, fill: Color? = null, ink: Color? = null, glyph: String? = null) {
    val b = LocalBoard.current
    Row(Modifier.clip(Pill).background(fill ?: b.chip).border(1.dp, b.line, Pill).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (glyph != null) StrategyGlyph(glyph, size = 10.dp)
        Text(text, style = Type.chip, color = ink ?: b.ink, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(chips: List<String>, gold: String? = null) {
    val b = LocalBoard.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (chip in chips) if (chip == gold) Chip(chip, b.gold, b.onGold) else Chip(chip)
    }
}

/**
 * Yardstick bars. Charts stay left to right in both languages (item 39). Each bar grows in on arrival and slides to its
 * new length when the measure changes.
 */
@Composable
fun Bars(rows: List<BarRow>, tr: Tr, mine: Color = LocalBoard.current.buy) {
    val b = LocalBoard.current
    if (rows.all { it.value == null }) {
        Note(tr.t(Tr.NOT_YET))
        return
    }
    val top = rows.mapNotNull { it.value?.let { v -> kotlin.math.abs(v) } }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val grow by rememberDraw(Unit, delayMs = 250)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for ((i, r) in rows.withIndex()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, style = Type.small.copy(textDirection = TextDirection.Content), color = if (r.mine) b.ink else b.muted,
                    modifier = Modifier.width(112.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val target = ((r.value?.let { v -> kotlin.math.abs(v) } ?: 0.0) / top).toFloat().coerceIn(0.02f, 1f)
                val share by animateFloatAsState(target, Motion.arrive, label = "bar")
                Box(Modifier.weight(1f).height(8.dp)) {
                    val color = when {
                        (r.value ?: 0.0) < 0 -> b.sell
                        r.mine -> mine
                        else -> b.muted.copy(alpha = listOf(1f, 0.55f, 0.75f, 0.4f)[i % 4])
                    }
                    Box(Modifier.fillMaxWidth(share * grow).height(8.dp).clip(Pill).background(color))
                }
                Text(signed(r.value, tr), style = Type.small, color = if ((r.value ?: 0.0) < 0) b.sell else b.ink, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
            }
        }
    }
}

/**
 * A choice of a few, whose highlight slides to the option pressed. A plain Box that measures its own width: the height
 * comes from the options (IntrinsicSize.Min), which a BoxWithConstraints cannot answer, and asking it crashed the page.
 */
@Composable
fun Seg(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val b = LocalBoard.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var widthPx by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(Pill).background(b.chip).border(1.dp, b.line, Pill).padding(4.dp)
        .onSizeChanged { widthPx = it.width }) {
        val w = with(density) { widthPx.toDp() } / options.size
        val x by animateDpAsState(w * selected, Motion.slide, label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(Pill).background(b.accent))
        Row(Modifier.fillMaxWidth()) {
            for ((i, o) in options.withIndex()) Box(
                Modifier.weight(1f).clip(Pill).clickable(role = Role.RadioButton) { onPick(i) }.heightIn(min = 36.dp).padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, style = Type.chip, textAlign = TextAlign.Center, color = if (i == selected) b.onAccent else b.ink, maxLines = 1) }
        }
    }
}

/** The days, closes and call days of one stock, for its chart. */
data class ChartData(val days: List<Long>, val closes: List<Double>, val callDays: Set<Long>)

/**
 * The chart card: the range and what the line adds up to across it, the Levels switch, the chart itself with the call
 * dots, the legend and the range choice. The same on the call page, a pick's page and the stock page.
 */
@Composable
fun ChartCard(data: ChartData, levels: PlanLevels?, tr: Tr, order: Int = 0, height: Dp = 170.dp, source: String? = null,
    range: ChartRange? = null, onRange: ((ChartRange) -> Unit)? = null) {
    val b = LocalBoard.current
    var own by rememberSaveable { mutableStateOf(ChartRange.YEAR) }
    val chosen = range ?: own
    var showLevels by rememberSaveable { mutableStateOf(true) }
    val n = minOf(chosen.sessions, data.closes.size)
    val closes = data.closes.takeLast(n)
    val days = data.days.takeLast(n)
    BoardCard(order = order) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(rangeName(chosen, tr), style = Type.small, color = b.muted)
                if (closes.size > 1) Text(signedPercent(closes.last() / closes.first() - 1, tr), style = Type.figure,
                    color = if (closes.last() >= closes.first()) b.ink else b.sell)
            }
            if (levels != null && !levels.isEmpty) {
                val on = showLevels
                Row(Modifier.clip(Pill).background(if (on) b.ink else b.chip).border(1.dp, b.line, Pill).clickable(role = Role.Switch) { showLevels = !on }
                    .heightIn(min = 44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (on) TickGlyph(b.page)
                    Text(tr.t("Levels"), style = Type.bodyStrong, color = if (on) b.page else b.ink)
                }
            }
        }
        if (closes.size < 2) Note(tr.t("No prices stored yet."))
        else {
            val marks = days.withIndex().filter { it.value in data.callDays }.map { it.index }.toSet()
            val axis = listOf(days.first(), days[days.size / 2], days.last()).map { dayText(EgxTime.date(it), tr) }
            androidx.compose.runtime.key(chosen) { PriceChart(closes, if (showLevels) levels else null, marks, axis, tr, height = height) }
            val legend = listOfNotNull(tr.t("A call was made").takeIf { marks.isNotEmpty() }, source?.let { tr.t("levels from %1\$s", it) }.takeIf { showLevels && levels != null }).joinToString(" · ")
            if (legend.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (marks.isNotEmpty()) Dot(b.ink)
                Note(legend)
            }
        }
        Seg(ChartRange.entries.map { rangeName(it, tr) }, ChartRange.entries.indexOf(chosen)) { i -> ChartRange.entries[i].let { if (onRange != null) onRange(it) else own = it } }
    }
}

// ---- the call page (item 31) ----

@Composable
private fun CallHero(v: CallView, tr: Tr, onStock: () -> Unit, big: Boolean = false) {
    val b = LocalBoard.current
    BoardCard(brush = if (v.buy) b.buyBrush else b.sellBrush) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(v.action, style = if (big) Type.action.copy(fontSize = Type.action.fontSize * 1.3f, lineHeight = Type.action.lineHeight * 1.3f) else Type.action,
                color = if (v.buy) b.buy else b.sell)
            if (!big) Chip(v.strategy, glyph = v.call.strategy)
        }
        Text(v.name, style = Type.name, color = b.ink, modifier = Modifier.clickable(role = Role.Button, onClick = onStock))
        Text("${v.call.code} · ${v.closeLine} · ${tr.t("Chart ›")}", style = Type.small, color = b.muted, modifier = Modifier.clickable(role = Role.Button, onClick = onStock))
        if (big) Chip(v.strategy, glyph = v.call.strategy)
        Spacer(Modifier.height(2.dp))
        Chips(v.chips, gold = v.chips.lastOrNull().takeIf { v.call.timing.isNotEmpty() })
    }
}

@Composable
private fun LadderCard(v: CallView, tr: Tr, order: Int, height: Dp = 260.dp, note: Boolean = true) {
    val b = LocalBoard.current
    val levels = PlanLevels.of(v.call)
    BoardCard(tr.t("The four levels"), order = order, trailing = levels.rewardToRisk?.let { r ->
        { Chip(tr.t("Reward %1\$s to 1 risk", tr.digits(String.format(java.util.Locale.US, "%.1f", r))), fill = b.accent1, ink = b.onAccent) }
    }) {
        PriceLadder(callRungs(v.call, tr), v.call.close, tr, height = height)
        if (note) Note(v.levelsNote)
    }
}

/** When to place the order, on the gold that marks timing everywhere. */
@Composable
fun WhenCard(title: String, text: String, order: Int) {
    val b = LocalBoard.current
    BoardCard(brush = Brush.linearGradient(listOf(b.gold, b.gold.copy(alpha = 0.85f))), order = order) {
        Text(title, style = Type.chip, color = b.onGold)
        Text(text, style = Type.body, color = b.onGold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallPage(v: CallView, posture: Posture, tr: Tr, onBack: () -> Unit, onCopyAndOpen: () -> Unit, onTook: (Double, Long) -> Unit, onStock: () -> Unit = {},
    chart: ChartData? = null) {
    val b = LocalBoard.current
    var sheet by remember { mutableStateOf(false) }
    val acts = v.call.action != "keep"
    val levels = PlanLevels.of(v.call).takeIf { !it.isEmpty }
    run {
        val buttons: @Composable (Boolean) -> Unit = { tall ->
            if (acts) {
                PrimaryButton(tr.t("Copy amount and open Thndr"), tall = tall, onClick = onCopyAndOpen)
                SecondButton(tr.t("I took it"), tall = tall) { sheet = true }
            }
        }
        val details: @Composable ColumnScope.() -> Unit = {
            if (v.whenText.isNotEmpty()) WhenCard(tr.t("When to place it"), v.whenText, 3)
            BoardCard(tr.t("Amount"), order = 4) { for ((k, value) in v.amount) Kv(k, value); Note(v.amountNote) }
            if (v.dividend.isNotEmpty()) BoardCard(tr.t("Dividend dates"), order = 5) { for ((k, value) in v.dividend) Kv(k, value); v.dividendNote?.let { Note(it) } }
            BoardCard(tr.t("Why"), order = 6) { Text(v.reason, style = Type.body, color = b.ink) }
        }
        val chartCard: @Composable () -> Unit = {
            if (chart != null && chart.closes.size > 1) ChartCard(chart, levels, tr, order = 1, source = v.strategy)
        }
        when (posture) {
            Posture.FOLDED -> Scroll {
                Back(tr.t("Today"), onBack)
                CallHero(v, tr, onStock)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { if (acts) { Box(Modifier.weight(1.7f)) { PrimaryButton(tr.t("Copy amount and open Thndr"), onClick = onCopyAndOpen) }; Box(Modifier.weight(1f)) { SecondButton(tr.t("I took it")) { sheet = true } } } }
                chartCard()
                if (levels != null) LadderCard(v, tr, 2)
                details()
            }
            Posture.UNFOLDED -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { Scroll { Back(tr.t("Today"), onBack); CallHero(v, tr, onStock, big = true); buttons(false); details() } }
                Box(Modifier.weight(1f)) { Scroll { Spacer(Modifier.height(40.dp)); chartCard(); if (levels != null) LadderCard(v, tr, 2) } }
            }
            Posture.TABLETOP -> Tabletop({
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(0.8f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { Back(tr.t("Today"), onBack); CallHero(v, tr, onStock, big = true) }
                    Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState())) { chartCard() }
                }
            }) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (acts) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(2f)) { PrimaryButton(tr.t("Copy amount and open Thndr"), tall = true, onClick = onCopyAndOpen) }
                        Box(Modifier.weight(1f)) { SecondButton(tr.t("I took it"), tall = true) { sheet = true } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (levels != null) Box(Modifier.weight(1.5f)) { LadderCard(v, tr, 2, height = 200.dp, note = false) }
                        Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(12.dp)) { details() }
                    }
                }
            }
        }
    }
    if (sheet) {
        var price by remember { mutableStateOf(tr.price(v.call.close)) }
        var shares by remember { mutableStateOf(v.call.shares.toString()) }
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = b.cardTop) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr.t("I took it"), style = Type.name, color = b.ink)
                OutlinedTextField(price, { price = it }, label = { Text(tr.t("Price I paid")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(shares, { shares = it }, label = { Text(tr.t("Shares")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                val p = price.replace(',', '.').toDoubleOrNull()
                if (p != null && p > 0) Note(tr.t("Against the paper call: %1\$s", signed(-copyGap(v.call, p), tr)))
                PrimaryButton(tr.t("Save to My copies")) {
                    val n = shares.toLongOrNull()
                    if (p != null && p > 0 && n != null && n > 0) { onTook(p, n); sheet = false }
                }
                SecondButton(tr.t("Cancel")) { sheet = false }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ---- Strategies and an evidence page (item 32) ----

/** A strategy's card on the list: its mark and name, its result in its own colour with its curve, and the yardsticks. */
@Composable
private fun StrategyCard(s: StrategyView, tr: Tr, order: Int, selected: Boolean, onOpen: () -> Unit) {
    val b = LocalBoard.current
    val color = b.strategyColor(s.strategy.id)
    BoardCard(order = order, onClick = onOpen, modifier = if (selected) Modifier.border(2.dp, color, CardShape) else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrategyGlyph(s.strategy.id, size = 14.dp)
            Text(s.name, style = Type.nameSmall, color = b.ink, modifier = Modifier.weight(1f))
            Chip(s.tierLine.substringBefore(" · "))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.bars.first().value?.let { signedPercent(it, tr) } ?: "–", style = Type.figure, color = color)
            Sparkline(s.curve, color, Modifier.weight(1f).height(34.dp))
        }
        Bars(s.bars, tr, mine = color)
    }
}

/** A compact row of the list, for the unfolded page: mark, name and result; the chosen one is filled with the gradient. */
@Composable
private fun StrategyRow(name: String, id: String?, result: String, selected: Boolean, order: Int, onOpen: () -> Unit) {
    val b = LocalBoard.current
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().arrive(order).pressable(source).clip(RoundedCornerShape(18.dp))
            .background(if (selected) b.accent else b.cardBrush).border(1.dp, b.line, RoundedCornerShape(18.dp))
            .clickable(source, null, role = Role.Button, onClick = onOpen).heightIn(min = 54.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (id != null) StrategyGlyph(id, size = 14.dp)
        Text(name, style = Type.bodyStrong, color = if (selected) b.onAccent else b.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(result, style = Type.figureSmall, color = if (selected) b.onAccent else id?.let { b.strategyColor(it) } ?: b.violet)
    }
}

@Composable
fun StrategiesPage(views: List<StrategyView>, posture: Posture, tr: Tr, chosen: String?, picks: List<PickEvidenceView> = emptyList(), onChoose: (String?) -> Unit) {
    val b = LocalBoard.current
    val pickList = picks.firstOrNull { it.list.id == chosen }
    val pick = views.firstOrNull { it.strategy.id == chosen } ?: views.first()
    val groups = listOf(Tier.PROMISING, Tier.WATCH, Tier.TESTING).map { tier -> tier to views.filter { if (tier == Tier.PROMISING) it.tier.makesCalls else it.tier == tier } }.filter { it.second.isNotEmpty() }
    val result = { s: StrategyView -> s.bars.first().value?.let { signedPercent(it, tr) } ?: "–" }
    val footer: @Composable ColumnScope.() -> Unit = {
        Note(tr.t("A strategy moves up or down only by its own record against the three yardsticks: cash, the EGX30, and eight random stocks picked the same days. %1\$d versions have been tried in all, so some of any good result is luck.", EgxStrategies.triedCount))
    }
    val evidence: @Composable ColumnScope.(Boolean) -> Unit = { back ->
        if (pickList != null) PickEvidencePage(pickList, tr, if (back) ({ onChoose(null) }) else null)
        else EvidencePage(pick, tr, if (back) ({ onChoose(null) }) else null)
    }
    when (posture) {
        Posture.FOLDED -> if (chosen != null) Scroll { evidence(true) } else Scroll {
            Lead(tr.t("Three give calls on Today. Two are recorded and watched. One runs as a control."))
            var order = 0
            for ((tier, group) in groups) {
                Text(tierLine(tier, tr), style = Type.small, color = b.muted, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                for (s in group) StrategyCard(s, tr, order++, false) { onChoose(s.strategy.id) }
            }
            if (picks.isNotEmpty()) {
                Text(tr.t("Technical picks · chart setups, on paper"), style = Type.small, color = b.muted, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                for (p in picks) PickListRow(p, tr, false) { onChoose(p.list.id) }
            }
            footer()
        }
        Posture.UNFOLDED -> Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                Scroll {
                    var order = 0
                    for ((tier, group) in groups) {
                        Text(tierLine(tier, tr), style = Type.small, color = b.muted, modifier = Modifier.padding(top = 4.dp))
                        for (s in group) StrategyRow(s.name, s.strategy.id, result(s), pickList == null && s.strategy.id == pick.strategy.id, order++) { onChoose(s.strategy.id) }
                    }
                    if (picks.isNotEmpty()) {
                        Text(tr.t("Technical picks · chart setups, on paper"), style = Type.small, color = b.muted, modifier = Modifier.padding(top = 4.dp))
                        for (p in picks) StrategyRow(p.name, null, p.bars.first().value?.let { signedPercent(it, tr) } ?: "–", p.list.id == chosen, order++) { onChoose(p.list.id) }
                    }
                    footer()
                }
            }
            Box(Modifier.weight(1f)) { Scroll { evidence(false) } }
        }
        Posture.TABLETOP -> Tabletop({ Scroll { evidence(false) } }) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var order = 0
                for ((_, group) in groups) for (s in group) StrategyTile(s.name, s.strategy.id, result(s), s.tierLine.substringBefore(" · "),
                    pickList == null && s.strategy.id == pick.strategy.id, order++) { onChoose(s.strategy.id) }
                for (p in picks) StrategyTile(p.name, null, p.bars.first().value?.let { signedPercent(it, tr) } ?: "–", tr.t("Testing"), p.list.id == chosen, order++) { onChoose(p.list.id) }
            }
        }
    }
}

/** A tile of the tabletop row: mark, name, result and tier, big enough for a thumb. */
@Composable
private fun StrategyTile(name: String, id: String?, result: String, tier: String, selected: Boolean, order: Int, onOpen: () -> Unit) {
    val b = LocalBoard.current
    val source = remember { MutableInteractionSource() }
    val ink = if (selected) b.onAccent else b.ink
    Column(
        Modifier.width(124.dp).heightIn(min = 140.dp).arrive(order).pressable(source).clip(RoundedCornerShape(20.dp))
            .background(if (selected) b.accent else b.cardBrush).border(1.dp, b.line, RoundedCornerShape(20.dp))
            .clickable(source, null, role = Role.Button, onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (id != null) StrategyGlyph(id, size = 14.dp) else Spacer(Modifier.size(14.dp))
        Text(name, style = Type.bodyStrong, color = ink, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
        Text(result, style = Type.figureSmall, color = if (selected) b.onAccent else id?.let { b.strategyColor(it) } ?: b.violet)
        Text(tier, style = Type.small, color = if (selected) b.onAccent else b.muted)
    }
}

@Composable
fun ColumnScope.EvidencePage(s: StrategyView, tr: Tr, onBack: (() -> Unit)?) {
    val b = LocalBoard.current
    val color = b.strategyColor(s.strategy.id)
    if (onBack != null) Back(tr.t("Strategies"), onBack)
    Chip(s.tierLine)
    Title(s.name, color)
    Lead(s.summary)
    BoardCard(tr.t("On paper since the ledger started"), order = 1) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.bars.first().value?.let { signedPercent(it, tr) } ?: "–", style = Type.figure, color = color)
            Sparkline(s.curve, color, Modifier.weight(1f).height(48.dp))
        }
        Bars(s.bars, tr, mine = color)
        Note(tr.t("%1\$d calls · holds %2\$d stocks now", s.calls, s.holds))
    }
    val r = Evidence.BY_ID[s.strategy.id]
    if (r != null) {
        BoardCard(tr.t("In the research: what 1.00 became"), order = 2) {
            fun f(x: Double) = tr.digits(String.format(java.util.Locale.US, "%.2f", x))
            val top = maxOf(r.old, r.recent, Evidence.CASH_OLD, Evidence.EGX30_RECENT)
            fun rows(vararg v: Pair<String, Double>) = v.map { (k, x) -> BarRow(k, x / top - 0.0, mine = k == s.name) }
            Note(tr.ltr("2015–2022"))
            ResearchBars(rows(s.name to r.old, tr.t("Cash") to Evidence.CASH_OLD, tr.t("The 30 busiest") to Evidence.MARKET_OLD), ::f, top, color)
            Note(tr.ltr("2023–2026"))
            ResearchBars(rows(s.name to r.recent, tr.t("Cash") to Evidence.CASH_RECENT, "EGX30" to Evidence.EGX30_RECENT), ::f, top, color)
            Note(tr.t("191 stocks, 2010 to 2026, costs included. Each year used only settings that had worked in earlier years."))
        }
        if (r.levelsOld != null && r.levelsRecent != null) BoardCard(tr.t("Selling at the targets"), order = 3) {
            Kv(tr.t("With targets 5 and 10"), tr.digits(String.format(java.util.Locale.US, "%.2f · %.2f", r.levelsOld, r.levelsRecent)))
            Note(tr.t("Selling at the targets cost about half the gain in the research. You chose the targets on 14 Nov; the ledger keeps both records."))
        }
        r.worstFall?.let { fall ->
            BoardCard(order = 4) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Note(tr.t("Worst fall on the way")); Text(signedPercent(fall, tr), style = Type.figureSmall, color = b.sell) }
                    Text(tr.t("A test on the past, not a promise."), style = Type.small, color = b.muted, modifier = Modifier.width(150.dp))
                }
            }
        }
    }
    BoardCard(tr.t("What to keep in mind"), order = 5) {
        Note(tr.t("Prices come from Yahoo's free feed and were cleaned. Delisted stocks are missing. Costs are 0.5% a round trip for the 30 busiest stocks and 0.8% for the rest."))
    }
}

/** What 1.00 became, as bars to one scale, with the figure at the end. */
@Composable
private fun ResearchBars(rows: List<BarRow>, figure: (Double) -> String, top: Double, mine: Color) {
    val b = LocalBoard.current
    val grow by rememberDraw(Unit, delayMs = 250)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (r in rows) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, style = Type.small.copy(textDirection = TextDirection.Content), color = if (r.mine) b.ink else b.muted,
                    modifier = Modifier.width(112.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(8.dp)) {
                    Box(Modifier.fillMaxWidth(((r.value ?: 0.0).toFloat() * grow).coerceIn(0.02f, 1f)).height(8.dp).clip(Pill)
                        .background(if (r.mine) mine else b.muted.copy(alpha = 0.6f)))
                }
                Text(figure((r.value ?: 0.0) * top), style = Type.small, color = b.ink, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            }
        }
    }
}

// ---- Ledger (item 33) ----

@Composable
fun LedgerPage(v: LedgerView, posture: Posture, tr: Tr, onStock: (String) -> Unit = {}, real: RealTermsView? = null) {
    val b = LocalBoard.current
    var rule by remember { mutableStateOf(false) }
    var measure by remember { mutableStateOf(Measure.POUNDS) }
    val a: @Composable ColumnScope.() -> Unit = {
        if (v.since.isNotEmpty()) Note(v.since)
        Lead(v.lead)
        BoardCard(tr.t("Paper account"), order = 0) {
            val shown = if (rule) v.byRule else v.account
            Text(tr.t("EGP %1\$s", tr.pounds(rememberCount(shown))), style = Type.figure, color = b.ink)
            if (v.started && real != null && real.known) {
                Note(tr.t("Shown in"))
                Seg(listOf(tr.t("Pounds"), tr.t("Dollars"), tr.t("Gold")), measure.ordinal) { measure = Measure.entries[it] }
                Bars(real.bars.getValue(measure), tr)
            } else if (v.started) Bars(v.accountBars, tr)
        }
        if (v.started && real != null && real.known) BoardCard(tr.t("In real terms"), order = 1) {
            for ((k, value) in real.became) Kv(k, value)
            Note(real.note)
        }
        if (v.started) BoardCard(tr.t("Two records"), order = 2) {
            Seg(listOf(tr.t("With targets"), tr.t("By the rule alone")), if (rule) 1 else 0) { rule = it == 1 }
            Kv(tr.t("With targets"), tr.t("EGP %1\$s", tr.pounds(v.withTargets)))
            Kv(tr.t("By the rule alone"), tr.t("EGP %1\$s", tr.pounds(v.byRule)))
            Note(v.targetsCost)
        }
        if (v.started) Text(tr.t("By strategy"), style = Type.small, color = b.muted)
        if (v.started) for ((i, s) in v.byStrategy.withIndex()) BoardCard(order = 3 + i) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrategyGlyph(s.strategy.id)
                Text(s.name, style = Type.body, color = b.ink, modifier = Modifier.weight(1f))
                Text(s.equity?.let { tr.t("EGP %1\$s", tr.pounds(it)) } ?: "–", style = Type.bodyStrong, color = b.ink)
            }
            Bars(s.bars, tr, mine = b.strategyColor(s.strategy.id))
        }
    }
    val c: @Composable ColumnScope.() -> Unit = {
        if (v.started) BoardCard(tr.t("Watched, not in the account"), order = 1) { for (s in v.watched) Kv(s.name, signed(s.bars.first().value, tr)) }
        BoardCard(tr.t("My copies"), order = 2) {
            Text(v.copiesLine, style = Type.body, color = b.ink)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column { for (cp in v.copies.take(20)) Box(Modifier.clickable(role = Role.Button) { onStock(cp.code) }) { Kv("${dayText(EgxTime.date(cp.day), tr)} · ${cp.code} · ${if (cp.buy) tr.t("Buy") else tr.t("Sell")}", "${tr.count(cp.shares)} @ ${tr.price(cp.price)}") } }
            }
        }
        if (v.started) BoardCard(tr.t("Costs"), order = 3) {
            Kv(tr.t("Paid so far"), tr.t("EGP %1\$s", tr.pounds(v.costsPaid)))
            Kv(tr.t("Account at double costs"), tr.t("EGP %1\$s", tr.pounds(v.atDouble)))
            Kv(tr.t("Dividends paid in, after 5% tax"), tr.t("EGP %1\$s", tr.pounds(v.dividends)))
        }
    }
    Panes(posture, a, c)
}

// ---- Settings (items 26 and 40) ----

@Composable
fun SettingsPage(p: EgxPrefs, posture: Posture, tr: Tr, onChange: ((EgxPrefs) -> EgxPrefs) -> Unit, onRunNow: () -> Unit,
    update: UpdateState = UpdateState.Idle(null), version: String = "", onUpdate: () -> Unit = {}) {
    val a: @Composable ColumnScope.() -> Unit = {
        BoardCard(tr.t("Money I trade with"), order = 0) {
            NumberField(String.format(java.util.Locale.US, "%.0f", p.money), tr.t("EGP")) { v -> v.toDoubleOrNull()?.takeIf { it > 0 }?.let { m -> onChange { it.copy(money = m) } } }
            Note(tr.t("Every call's pounds and shares come from this, split equally among the strategies that are on: %1\$s each.", tr.pounds(p.money / maxOf(1, p.on.size))))
        }
        BoardCard(tr.t("Cash rate"), order = 1) {
            NumberField(String.format(java.util.Locale.US, "%.1f", 100 * p.cashRate), tr.t("% a year")) { v -> v.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.let { r -> onChange { it.copy(cashRate = r / 100) } } }
            Note(tr.t("Money that is not in a stock earns this on paper. Set it to your money-market fund's rate."))
        }
        BoardCard(tr.t("Technical picks"), order = 2) {
            NumberField(String.format(java.util.Locale.US, "%.0f", p.pickAmount), tr.t("EGP")) { v -> v.toDoubleOrNull()?.takeIf { it > 0 }?.let { m -> onChange { it.copy(pickAmount = m) } } }
            Note(tr.t("The pounds for one pick: each card's shares come from this. Picks are on paper and never part of the money above."))
            for (l in EgxStrategies.technical) {
                Toggle(tr.t(l.name), tr.t("Testing · on paper"), l.id in p.picksOn, true) { on ->
                    onChange { it.copy(picksOn = if (on) it.picksOn + l.id else it.picksOn - l.id) }
                }
            }
        }
        BoardCard(tr.t("Strategies that give calls"), order = 3) {
            for (s in EgxStrategies.all.filter { !it.silent }) {
                val allowed = s.launchTier.makesCalls
                Toggle(tr.t(s.name), if (allowed) tierLine(Tier.PROMISING, tr) else tr.t("Watch: can be turned on once its record earns it"), s.id in p.on, allowed, glyph = s.id) { on ->
                    onChange { it.copy(on = if (on) it.on + s.id else it.on - s.id) }
                }
            }
        }
    }
    val c: @Composable ColumnScope.() -> Unit = {
        BoardCard(tr.t("Language"), order = 1) {
            Seg(listOf(tr.t("Arabic"), tr.t("English"), tr.t("Follow the phone")), when (p.language) { Language.ARABIC -> 0; Language.ENGLISH -> 1; else -> 2 }) { i ->
                onChange { it.copy(language = listOf(Language.ARABIC, Language.ENGLISH, Language.FOLLOW_PHONE)[i]) }
            }
            Toggle(tr.t("Arabic-Indic digits"), tr.t("Show ١٢٣ instead of 123"), p.arabicDigits, true) { on -> onChange { it.copy(arabicDigits = on) } }
        }
        BoardCard(tr.t("Costs"), order = 2) {
            Toggle(tr.t("Also show everything at double costs"), tr.t("Fees and stamp duty 0.16% a round trip, plus the spread: 0.5% in all for the 30 busiest, 0.8% for the rest."), p.doubleCosts, true) { on -> onChange { it.copy(doubleCosts = on) } }
            Kv(tr.t("Free orders a month"), tr.digits("$FREE_ORDERS"))
        }
        BoardCard(tr.t("Days the exchange is closed"), order = 3) {
            Note(tr.t("Fixed public holidays are known. Add the Eids, Sham el-Nessim and any moved holiday, as yyyy-mm-dd, one a line."))
            var text by remember(p.holidays) { mutableStateOf(p.holidays.sorted().joinToString("\n")) }
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            SecondButton(tr.t("Save holidays")) {
                val days = text.lines().mapNotNull { runCatching { java.time.LocalDate.parse(it.trim()) }.getOrNull() }.toSet()
                onChange { it.copy(holidays = days) }
            }
        }
        UpdatesCard(update, version, tr, onUpdate)
        SecondButton(tr.t("Run the evening pass now"), onClick = onRunNow)
        BoardCard { Note(tr.t("Signal Lab EGX is a research tool. Every call is on paper; no real money is traded by the app. Not financial advice.")) }
    }
    Panes(posture, a, c)
}

@Composable
private fun NumberField(value: String, unit: String, onDone: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(text, { text = it; onDone(it.replace(',', '.')) }, suffix = { Text(unit) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Toggle(name: String, line: String, on: Boolean, enabled: Boolean, glyph: String? = null, onSet: (Boolean) -> Unit) {
    val b = LocalBoard.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (glyph != null) StrategyGlyph(glyph)
        Column(Modifier.weight(1f)) { Text(name, style = Type.body, color = if (enabled) b.ink else b.muted); Note(line) }
        Switch(on, onSet, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = b.accent1, checkedThumbColor = b.onAccent,
            uncheckedTrackColor = b.chip, uncheckedBorderColor = b.line, uncheckedThumbColor = b.muted))
    }
}

// ---- the month-end list (item 34) ----

@Composable
fun MonthEndPage(v: MonthEndView, posture: Posture, tr: Tr, names: (String) -> String, onBack: () -> Unit, onStock: (String) -> Unit = {}, onPlaced: (CallRow) -> Unit) {
    val b = LocalBoard.current
    val head: @Composable ColumnScope.() -> Unit = {
        Back(tr.t("Today"), onBack)
        Title(v.title)
        BoardCard(tr.t("Free orders"), order = 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OrdersRing(v.ordersUsed, FREE_ORDERS, tr)
                Note(tr.t("This list uses %1\$d of them.", v.listOrders))
            }
        }
        BoardCard(tr.t("Money"), order = 1) {
            Kv(tr.t("The sells bring"), tr.t("EGP %1\$s", tr.pounds(v.bring)))
            Kv(tr.t("The buys need"), tr.t("EGP %1\$s", tr.pounds(v.need)))
            Note(tr.t("Sell first at the open; buy near the close the same day."))
        }
    }
    val lists: @Composable ColumnScope.() -> Unit = {
        @Composable
        fun row(call: CallRow, order: Int) {
            val buy = call.action == "buy"
            BoardCard(brush = if (buy) b.buyBrush else b.sellBrush, order = order) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(role = Role.Button) { onStock(call.code) }) {
                        Text(names(call.code), style = Type.nameSmall, color = b.ink)
                        Note("${call.code} · ${EgxStrategies.of(call.strategy)?.name?.let { tr.t(it) } ?: ""} · ${tr.t("%1\$d shares", call.shares)} · ${tr.t("EGP %1\$s", tr.pounds(call.pounds))}")
                    }
                    Text(if (buy) tr.t("Buy") else tr.t("Sell"), style = Type.actionSmall, color = if (buy) b.buy else b.sell)
                    Spacer(Modifier.width(10.dp))
                    PlacedMark(call.placed, if (buy) b.buy else b.sell, tr) { onPlaced(call) }
                }
            }
        }
        var order = 2
        if (v.sells.isNotEmpty()) Note(tr.t("Sell these"))
        for (s in v.sells) row(s, order++)
        if (v.buys.isNotEmpty()) Note(tr.t("Buy these"))
        for (x in v.buys) row(x, order++)
        if (v.keeps.isNotEmpty()) BoardCard(tr.t("Keep · no order needed"), order = order) { for (k in v.keeps) Kv(names(k.code), k.code) }
    }
    Panes(posture, head, lists)
}

/** The round mark of an order placed: empty, or a tick stamped in. */
@Composable
fun PlacedMark(placed: Boolean, color: Color, tr: Tr, onToggle: () -> Unit) {
    val stamp = rememberStamp(placed)
    Box(
        Modifier.size(44.dp).clip(Pill).border(2.5.dp, color, Pill).clickable(role = Role.Checkbox, onClickLabel = tr.t("Placed the order"), onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (placed) Box(Modifier.stamped(stamp)) { TickGlyph(color, size = 24.dp) }
    }
}

/** The stamp's fall: from twice the size and turned, to rest at a slight angle. */
fun Modifier.stamped(p: Float): Modifier = graphicsLayer {
    val s = 2.2f - 1.2f * p
    scaleX = s
    scaleY = s
    rotationZ = -24f + 14f * p
    alpha = p.coerceIn(0f, 1f)
}

// ---- updates from GitHub ----

/** What the Updates card says for each state of the updater, and what its button does. */
fun updateTexts(state: UpdateState, tr: Tr): Triple<String, String?, String?> = when (state) {
    is UpdateState.Idle, is UpdateState.UpToDate -> Triple(
        if (state is UpdateState.UpToDate) tr.t("This is the newest version.") else tr.t("Looks for a newer version on GitHub once a day."),
        null, tr.t("Check for updates"))
    UpdateState.Checking -> Triple(tr.t("Checking GitHub…"), null, null)
    is UpdateState.Available -> Triple(tr.t("Version %1\$s is ready.", state.release.version.toString()), state.release.notes.take(400), tr.t("Install"))
    is UpdateState.Downloading -> Triple(tr.t("Downloading version %1\$s…", state.release.version.toString()), null, null)
    is UpdateState.NeedsPermission -> Triple(tr.t("Allow installs from Signal Lab EGX in Android's settings, then come back and tap Install."), null, tr.t("Install"))
    is UpdateState.Handed -> Triple(tr.t("Android's installer is open. It asks you before it installs."), null, tr.t("Install"))
    is UpdateState.Failed -> Triple(tr.t("The update could not be completed."), state.message, if (state.release != null) tr.t("Install") else tr.t("Check for updates"))
}

@Composable
fun UpdatesCard(state: UpdateState, version: String, tr: Tr, onTap: () -> Unit) {
    val b = LocalBoard.current
    val (line, detail, button) = updateTexts(state, tr)
    BoardCard(tr.t("Updates"), order = 4) {
        Kv(tr.t("Installed version"), tr.digits(version))
        Text(line, style = Type.body, color = b.ink)
        // GitHub's own words (versions, error codes) stay as they come; they are left to right.
        if (detail != null) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Note(detail) }
        if (button != null) SecondButton(button, onClick = onTap)
    }
}
